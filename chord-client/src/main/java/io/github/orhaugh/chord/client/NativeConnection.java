/*
 * Copyright 2026 Ross Haugh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.orhaugh.chord.client;

import io.github.orhaugh.chord.ChordConfigurationException;
import io.github.orhaugh.chord.ChordProtocolException;
import io.github.orhaugh.chord.RetryClass;
import io.github.orhaugh.chord.annotations.Experimental;
import io.github.orhaugh.chord.codec.block.BlockLimits;
import io.github.orhaugh.chord.codec.block.BlockWriter;
import io.github.orhaugh.chord.codec.compress.Compression;
import io.github.orhaugh.chord.codec.compress.FrameCompressingOutputStream;
import io.github.orhaugh.chord.codec.compress.FrameDecompressingInputStream;
import io.github.orhaugh.chord.protocol.ClientPacketType;
import io.github.orhaugh.chord.protocol.Progress;
import io.github.orhaugh.chord.protocol.ProtocolFeature;
import io.github.orhaugh.chord.protocol.ProtocolRevisions;
import io.github.orhaugh.chord.protocol.ServerError;
import io.github.orhaugh.chord.protocol.ServerPacketType;
import io.github.orhaugh.chord.protocol.handshake.ChunkedProtocolMode;
import io.github.orhaugh.chord.protocol.handshake.ClientHello;
import io.github.orhaugh.chord.protocol.handshake.HelloCodec;
import io.github.orhaugh.chord.protocol.handshake.ServerHello;
import io.github.orhaugh.chord.protocol.state.ConnectionState;
import io.github.orhaugh.chord.protocol.state.ConnectionStateMachine;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import io.github.orhaugh.chord.transport.ChunkedInputStream;
import io.github.orhaugh.chord.transport.ChunkedOutputStream;
import io.github.orhaugh.chord.transport.NativeTransport;
import io.github.orhaugh.chord.transport.TcpTransport;
import io.github.orhaugh.chord.transport.TlsTransport;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single native protocol connection: transport, handshake, authentication and ping.
 *
 * <p>This is the lowest level building block of CHord and the only entry point at the current stage
 * of the roadmap. Query execution, inserts and pooling build on top of it in later phases.
 *
 * <p>A native connection runs one protocol exchange at a time and is not thread safe; the only
 * operation permitted from another thread is {@link #close()}, which aborts a blocked exchange by
 * closing the transport. After any protocol violation, timeout or transport failure the connection
 * moves to {@link ConnectionState#BROKEN} and refuses further use; broken connections are never
 * reused, because the protocol position of the peer is unknowable.
 */
@Experimental
public final class NativeConnection implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(NativeConnection.class);
  private static final AtomicLong IDS = new AtomicLong();

  /** Client major version reported in ClientHello. */
  static final long CLIENT_VERSION_MAJOR = 0;

  /** Client minor version reported in ClientHello. */
  static final long CLIENT_VERSION_MINOR = 1;

  private final long id;
  private final NativeTransport transport;
  private final WireReader in;
  private final WireReader rawIn;
  private final WireWriter out;
  private final ServerHello serverHello;
  private final long negotiatedRevision;
  private final ConnectionStateMachine state;
  private final ConnectionOptions options;
  private final ChunkedOutputStream chunkedOut;
  private final Object sendLock = new Object();
  private final AtomicBoolean cancelRequested = new AtomicBoolean();
  private volatile ZoneId sessionTimezone;
  private WireReader compressedReader;
  private ActiveCompression activeCompression;
  private WireWriter activeFrameWriter;

  /** The compression selection of the active exchange: the method and its effective level. */
  private record ActiveCompression(Compression method, int level) {}

  private NativeConnection(
      long id,
      NativeTransport transport,
      WireReader in,
      WireReader rawIn,
      WireWriter out,
      ServerHello serverHello,
      long negotiatedRevision,
      ConnectionStateMachine state,
      ConnectionOptions options,
      ChunkedOutputStream chunkedOut) {
    this.id = id;
    this.transport = transport;
    this.in = in;
    this.rawIn = rawIn;
    this.out = out;
    this.serverHello = serverHello;
    this.negotiatedRevision = negotiatedRevision;
    this.state = state;
    this.options = options;
    this.chunkedOut = chunkedOut;
    this.sessionTimezone = parseZone(serverHello.timezone().orElse("UTC"));
  }

  private static ZoneId parseZone(String zoneName) {
    try {
      return ZoneId.of(zoneName);
    } catch (RuntimeException e) {
      LOG.warn("unknown server timezone \"{}\"; DateTime columns fall back to UTC", zoneName);
      return ZoneId.of("UTC");
    }
  }

  /**
   * Connects over plain TCP, or over TLS when {@link ConnectionOptions#tls()} is configured,
   * performs the handshake and authenticates. TLS connections verify the server hostname during the
   * handshake and carry passwords without any plaintext opt in.
   *
   * @param options connection configuration
   * @return a connection in {@link ConnectionState#READY}
   */
  public static NativeConnection open(ConnectionOptions options) {
    NativeTransport transport;
    try {
      if (options.tls().isPresent()) {
        transport =
            TlsTransport.connect(
                options.host(), options.port(), options.transportOptions(), options.tls().get());
      } else {
        // Fail fast for a statically configured password before dialling; supplier resolved
        // credentials are checked in the handshake, still before any hello bytes travel.
        requirePlaintextPasswordOptIn(
            options, ChordCredentials.of(options.username(), options.passwordChars()), false);
        transport =
            TcpTransport.connect(options.host(), options.port(), options.transportOptions());
      }
    } catch (RuntimeException e) {
      // Nothing reached the server; retrying a failed connect cannot duplicate work.
      classifyIfTransportLevel(e, RetryClass.SAFE_TO_RETRY);
      throw e;
    }
    return open(options, transport);
  }

  /**
   * Performs the handshake over an already connected transport. Package access for tests and future
   * transports; the transport is owned by the returned connection, and is closed here on any
   * handshake failure.
   */
  static NativeConnection open(ConnectionOptions options, NativeTransport transport) {
    JfrEvents.ConnectEvent event = new JfrEvents.ConnectEvent();
    event.begin();
    event.host = options.host();
    event.port = options.port();
    event.secure = transport.isSecure();
    long startNanos = System.nanoTime();
    try {
      NativeConnection connection = handshake(options, transport);
      event.negotiatedRevision = connection.negotiatedRevision();
      event.succeeded = true;
      return connection;
    } finally {
      event.commit();
      notifyOperationListener(
          options,
          listener ->
              listener.connectFinished(
                  event.succeeded, java.time.Duration.ofNanos(System.nanoTime() - startNanos)));
    }
  }

  /** Notifies the configured operation listener; a throwing listener never disturbs callers. */
  static void notifyOperationListener(
      ConnectionOptions options, java.util.function.Consumer<ChordOperationListener> callback) {
    ChordOperationListener listener = options.operationListener();
    if (listener == ChordOperationListener.NOOP) {
      return;
    }
    try {
      callback.accept(listener);
    } catch (RuntimeException e) {
      LOG.warn("operation listener failed", e);
    }
  }

  private static NativeConnection handshake(ConnectionOptions options, NativeTransport transport) {
    long id = IDS.incrementAndGet();
    ConnectionStateMachine state = new ConnectionStateMachine();
    ChordCredentials credentials = options.resolveCredentials();
    char[] password = credentials.passwordChars();
    try {
      requirePlaintextPasswordOptIn(options, credentials, transport.isSecure());
      state.transitionTo(ConnectionState.HANDSHAKING);
      WireWriter out = new WireWriter(transport.outputStream());
      WireReader in = new WireReader(transport.inputStream(), options.wireLimits());

      ClientHello clientHello =
          new ClientHello(
              options.clientName(),
              CLIENT_VERSION_MAJOR,
              CLIENT_VERSION_MINOR,
              options.advertisedRevision(),
              options.database(),
              credentials.username());
      HelloCodec.writeClientHello(out, clientHello, password);
      out.flush();

      ServerHello serverHello = HelloCodec.readServerHello(in, options.advertisedRevision());
      long negotiated =
          ProtocolRevisions.negotiate(options.advertisedRevision(), serverHello.serverRevision());

      boolean[] chunked = resolveFraming(serverHello, negotiated);
      boolean sendChunked = chunked[0];
      boolean receiveChunked = chunked[1];

      if (ProtocolFeature.ADDENDUM.enabledFor(negotiated)) {
        HelloCodec.writeAddendum(
            out,
            negotiated,
            options.quotaKey(),
            sendChunked ? ChunkedProtocolMode.CHUNKED : ChunkedProtocolMode.NOTCHUNKED,
            receiveChunked ? ChunkedProtocolMode.CHUNKED : ChunkedProtocolMode.NOTCHUNKED);
        out.flush();
      }

      // Chunked framing starts strictly after the addendum, per Connection::connect.
      ChunkedOutputStream chunkedOut = null;
      if (sendChunked) {
        chunkedOut = new ChunkedOutputStream(transport.outputStream());
        out = new WireWriter(chunkedOut);
      }
      WireReader rawIn = in;
      if (receiveChunked) {
        // Layer over the handshake reader, not the raw stream: its buffer may already hold
        // bytes that belong to the chunked stream.
        in = new WireReader(new ChunkedInputStream(in.asInputStream()), options.wireLimits());
      }

      NativeConnection connection =
          new NativeConnection(
              id, transport, in, rawIn, out, serverHello, negotiated, state, options, chunkedOut);
      state.transitionTo(ConnectionState.READY);
      LOG.debug(
          "connection {} established to {} ({} {} revision {}, negotiated {})",
          id,
          transport.remoteAddress(),
          serverHello.serverName(),
          serverHello.versionString(),
          serverHello.serverRevision(),
          negotiated);
      return connection;
    } catch (RuntimeException e) {
      if (state.transitionIfCurrent(ConnectionState.HANDSHAKING, ConnectionState.BROKEN)) {
        state.transitionTo(ConnectionState.CLOSED);
      } else if (state.state() == ConnectionState.NEW) {
        state.transitionTo(ConnectionState.CLOSED);
      }
      // A handshake that never completed executed nothing; server rejections such as
      // authentication failures keep their own classification.
      classifyIfTransportLevel(e, RetryClass.SAFE_TO_RETRY);
      transport.close();
      throw e;
    } finally {
      ConnectionOptions.wipe(password);
    }
  }

  /**
   * Stamps a retry classification on transport level failures: transport, timeout, protocol and
   * data corruption exceptions, whose meaning depends on the phase of the exchange. Server reported
   * and client local errors keep their own classification.
   */
  static void classifyIfTransportLevel(RuntimeException e, RetryClass classification) {
    if (e instanceof io.github.orhaugh.chord.ChordTransportException
        || e instanceof io.github.orhaugh.chord.ChordTimeoutException
        || e instanceof ChordProtocolException
        || e instanceof io.github.orhaugh.chord.ChordDataCorruptionException) {
      ((io.github.orhaugh.chord.ChordException) e).classifiedAs(classification);
    }
  }

  /**
   * The ClickHouse native protocol sends passwords verbatim inside the handshake, so a non empty
   * password over a plaintext transport is refused unless explicitly permitted.
   */
  private static void requirePlaintextPasswordOptIn(
      ConnectionOptions options, ChordCredentials credentials, boolean secure) {
    if (credentials.hasPassword() && !secure && !options.allowPlaintextPassword()) {
      throw new ChordConfigurationException(
          "Refusing to send a password over a plaintext connection. Use TLS, or explicitly opt"
              + " in with allowPlaintextPassword(true) if this network is secured by other"
              + " means.");
    }
  }

  /**
   * Resolves the chunked framing negotiation per channel. CHord prefers plain framing but accepts
   * chunked when the server requires it, so servers configured with strict {@code proto_caps} work.
   * The client send channel pairs with the server receive capability and vice versa.
   *
   * @return {@code [sendChunked, receiveChunked]}
   */
  private static boolean[] resolveFraming(ServerHello serverHello, long negotiated) {
    if (!ProtocolFeature.CHUNKED_PACKETS.enabledFor(negotiated)) {
      return new boolean[] {false, false};
    }
    ChunkedProtocolMode serverSend =
        ChunkedProtocolMode.fromWire(
            serverHello
                .chunkedSendCapability()
                .orElseThrow(
                    () ->
                        new ChordProtocolException(
                            "Server omitted its chunked send capability despite negotiating"
                                + " revision "
                                + negotiated)));
    ChunkedProtocolMode serverReceive =
        ChunkedProtocolMode.fromWire(
            serverHello
                .chunkedReceiveCapability()
                .orElseThrow(
                    () ->
                        new ChordProtocolException(
                            "Server omitted its chunked receive capability despite negotiating"
                                + " revision "
                                + negotiated)));
    boolean sendChunked =
        ChunkedProtocolMode.resolveChunked(
            serverReceive, ChunkedProtocolMode.NOTCHUNKED_OPTIONAL, "send");
    boolean receiveChunked =
        ChunkedProtocolMode.resolveChunked(
            serverSend, ChunkedProtocolMode.NOTCHUNKED_OPTIONAL, "recv");
    return new boolean[] {sendChunked, receiveChunked};
  }

  /** Marks a protocol packet boundary on the send channel; a no op without chunked framing. */
  void endPacket() {
    out.flush();
    if (chunkedOut != null) {
      try {
        chunkedOut.endMessage();
      } catch (java.io.IOException e) {
        throw new io.github.orhaugh.chord.ChordTransportException(
            "I/O failure concluding a chunked packet", e);
      }
    }
  }

  /**
   * Returns the identifier of this connection, used for log correlation.
   *
   * @return the connection id
   */
  public long id() {
    return id;
  }

  /**
   * Returns the decoded server half of the handshake.
   *
   * @return the server hello
   */
  public ServerHello serverHello() {
    return serverHello;
  }

  /**
   * Returns the protocol revision both sides operate at: the minimum of the advertised and server
   * revisions.
   *
   * @return the negotiated revision
   */
  public long negotiatedRevision() {
    return negotiatedRevision;
  }

  /**
   * Returns the current connection state.
   *
   * @return the state
   */
  public ConnectionState state() {
    return state.state();
  }

  /**
   * Returns the remote endpoint.
   *
   * @return the remote address
   */
  public SocketAddress remoteAddress() {
    return transport.remoteAddress();
  }

  /**
   * Executes a query and returns a streaming columnar result.
   *
   * <p>The connection is occupied until the result is closed or exhausted; the schema header is
   * available on the returned result before any row data is consumed. A server side error surfaces
   * as {@link io.github.orhaugh.chord.ChordServerException} from the first {@code nextBlock()} call
   * that encounters it (or from this method when the server rejects the query before sending any
   * data), and the connection remains usable afterwards because an Exception packet is a defined
   * stream terminator.
   *
   * @param request the query to execute
   * @return the streaming result, which must be closed
   */
  public QueryResult query(QueryRequest request) {
    state.transitionTo(ConnectionState.WRITING_QUERY);
    try {
      beginExchange(request);
      QueryCodec.writeQuery(
          out,
          request,
          options,
          negotiatedRevision,
          osUser(),
          localHostname(),
          activeCompression != null);
      endPacket();
      sendEmptyDataPacket();
      out.flush();
      state.transitionTo(ConnectionState.READING_RESPONSE);
      LOG.debug("connection {} query {} sent", id, request.queryId());
      return new NativeQueryResult(this, in, request);
    } catch (RuntimeException e) {
      // A server Exception packet received while priming the result concludes the stream
      // cleanly and has already returned the connection to READY; only failures that leave
      // the exchange unfinished poison the connection.
      ConnectionState current = state.state();
      if (current == ConnectionState.WRITING_QUERY || current == ConnectionState.READING_RESPONSE) {
        markBroken();
      }
      // The query may have begun executing before the failure.
      classifyIfTransportLevel(e, RetryClass.RETRY_ONLY_IF_IDEMPOTENT);
      throw e;
    }
  }

  private static String osUser() {
    return System.getProperty("user.name", "");
  }

  private static String localHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "";
    }
  }

  /** Chooses the effective compression for an exchange and resets per exchange streams. */
  private void beginExchange(QueryRequest request) {
    Compression connectionMethod = options.compression().orElse(null);
    Compression requestMethod = request.compression().orElse(null);
    Compression method = requestMethod != null ? requestMethod : connectionMethod;
    // The connection's tuned level only applies to its own method; a per query override uses
    // the override method's default level.
    int level =
        requestMethod != null && requestMethod != connectionMethod
            ? requestMethod.defaultLevel()
            : options.compressionLevel();
    activeCompression = method == null ? null : new ActiveCompression(method, level);
    activeFrameWriter = null;
    cancelRequested.set(false);
  }

  /**
   * Sends one Data packet carrying an empty block: the external tables terminator after a query and
   * the terminal block of an INSERT. The block body travels through the compressed stream when the
   * exchange is compressed, matching {@code Connection::sendData}.
   */
  void sendEmptyDataPacket() {
    out.writeVarUInt(ClientPacketType.DATA.code());
    out.writeString(""); // external table name; empty means the main stream
    if (activeCompression != null) {
      out.flush();
      WireWriter body = frameWriter();
      BlockWriter.writeEmpty(body, negotiatedRevision);
      body.flush();
    } else {
      BlockWriter.writeEmpty(out, negotiatedRevision);
    }
    endPacket();
  }

  /** Returns the writer for outgoing block bodies of the active exchange. */
  WireWriter blockBodyWriter() {
    return activeCompression != null ? frameWriter() : out;
  }

  private WireWriter frameWriter() {
    if (activeFrameWriter == null) {
      java.io.OutputStream sink = chunkedOut != null ? chunkedOut : transport.outputStream();
      activeFrameWriter =
          new WireWriter(
              new FrameCompressingOutputStream(
                  sink, activeCompression.method(), activeCompression.level()));
    }
    return activeFrameWriter;
  }

  /** Returns the raw packet reader. */
  WireReader packetReader() {
    return in;
  }

  /** Returns the reader for Data, Totals and Extremes bodies of the active exchange. */
  WireReader dataBodyReader() {
    return activeCompression != null ? compressedReader() : in;
  }

  /**
   * Returns the reader for Log, ProfileEvents and TableColumns bodies, which travel compressed only
   * from revision 54481 and only when the exchange is compressed.
   */
  WireReader auxiliaryBodyReader() {
    boolean compressed =
        activeCompression != null
            && ProtocolFeature.COMPRESSED_LOGS_PROFILE_EVENTS_COLUMNS.enabledFor(
                negotiatedRevision);
    return compressed ? compressedReader() : in;
  }

  private WireReader compressedReader() {
    if (compressedReader == null) {
      // Frames are pulled through the raw reader so its buffering stays consistent.
      compressedReader =
          new WireReader(
              new FrameDecompressingInputStream(in.asInputStream(), options.compressionLimits()),
              options.wireLimits());
    }
    return compressedReader;
  }

  /** Returns the block decode limits configured for this connection. */
  BlockLimits blockLimits() {
    return options.blockLimits();
  }

  /** Returns the current session timezone, as updated by TimezoneUpdate packets. */
  ConnectionOptions options() {
    return options;
  }

  ZoneId sessionTimezone() {
    return sessionTimezone;
  }

  /** Applies a TimezoneUpdate packet to the session. */
  void updateSessionTimezone(String zoneName) {
    this.sessionTimezone = parseZone(zoneName);
    LOG.debug("connection {} session timezone changed to {}", id, sessionTimezone);
  }

  /**
   * Begins a native INSERT: sends the query with pending data, waits for the server supplied schema
   * and returns the stream for sending blocks.
   *
   * <p>The connection is occupied until the stream is finished or closed. A server rejection before
   * data surfaces from this method as a typed exception and leaves the connection reusable; closing
   * the stream without {@link InsertStream#finish()} hard aborts the connection so partially
   * streamed rows are never committed implicitly.
   *
   * @param request the INSERT statement, for example {@code INSERT INTO t (a, b) VALUES}; data
   *     always travels as native blocks, never inline in the SQL
   * @return the insert stream, which must be finished or closed
   */
  public InsertStream insert(QueryRequest request) {
    state.transitionTo(ConnectionState.WRITING_QUERY);
    try {
      beginExchange(request);
      QueryCodec.writeQuery(
          out,
          request,
          options,
          negotiatedRevision,
          osUser(),
          localHostname(),
          activeCompression != null);
      endPacket();
      sendEmptyDataPacket();
      out.flush();
      state.transitionTo(ConnectionState.READING_RESPONSE);
      LOG.debug("connection {} insert {} sent", id, request.queryId());
      return new NativeInsertStream(this, in, out, request);
    } catch (RuntimeException e) {
      ConnectionState current = state.state();
      if (current == ConnectionState.WRITING_QUERY || current == ConnectionState.READING_RESPONSE) {
        markBroken();
      }
      // No data blocks were streamed yet, so a failed INSERT initiation wrote nothing; the
      // server aborts an insert whose connection drops before its data arrives.
      classifyIfTransportLevel(e, RetryClass.SAFE_TO_RETRY);
      throw e;
    }
  }

  /** Moves from awaiting the INSERT schema to streaming data blocks. */
  void beginInsertStreaming() {
    state.transitionTo(ConnectionState.WRITING_INSERT);
  }

  /** Moves from streaming INSERT blocks to reading the concluding response. */
  void finishInsertStreaming() {
    state.transitionTo(ConnectionState.READING_RESPONSE);
  }

  /** Marks a cleanly concluded response stream, returning the connection to READY. */
  void finishResponse() {
    state.transitionTo(ConnectionState.READY);
  }

  /** Marks the connection broken from the result pump. */
  void markBrokenPublic() {
    markBroken();
  }

  /**
   * Sends the Cancel packet for the in flight query, at most once per exchange. Serialised so a
   * cancel from another thread cannot interleave with one from the consuming thread; while a
   * response is being read the send channel is otherwise idle, which is what makes a cross thread
   * cancel safe.
   *
   * @return {@code true} when this call sent the packet, {@code false} when it was already sent or
   *     the connection is not reading a response
   */
  boolean requestCancel() {
    if (!cancelRequested.compareAndSet(false, true)) {
      return false;
    }
    synchronized (sendLock) {
      if (state.state() != ConnectionState.READING_RESPONSE) {
        return false;
      }
      try {
        out.writeVarUInt(ClientPacketType.CANCEL.code());
        endPacket();
        out.flush();
      } catch (RuntimeException e) {
        // A failed cancel write means the transport is gone; the reader will fail too.
        markBroken();
        throw e;
      }
      LOG.debug("connection {} cancel sent", id);
      return true;
    }
  }

  /**
   * Waits until the next response byte is available or the timeout elapses, consuming nothing.
   * Buffered bytes in the packet reader chain count as available; otherwise the transport is
   * polled. Only meaningful at packet boundaries.
   */
  boolean awaitResponseReadable(long timeoutMillis) {
    if (in.hasBufferedBytes() || (rawIn != in && rawIn.hasBufferedBytes())) {
      return true;
    }
    int clamped = (int) Math.min(Math.max(timeoutMillis, 1), Integer.MAX_VALUE);
    return transport.awaitReadable(clamped);
  }

  /** Returns the configured grace period for concluding a cancelled query, in milliseconds. */
  long cancelGraceMillis() {
    return options.cancelGrace().toMillis();
  }

  /**
   * Sends a Ping and waits for the Pong, verifying the connection is alive and in protocol sync.
   *
   * <p>Stale Progress packets that arrive before the Pong are consumed and discarded, matching the
   * behaviour of the official client. Any other packet, timeout or transport failure marks the
   * connection {@link ConnectionState#BROKEN} and raises the mapped exception.
   */
  public void ping() {
    state.transitionTo(ConnectionState.PINGING);
    try {
      out.writeVarUInt(ClientPacketType.PING.code());
      endPacket();
      out.flush();
      while (true) {
        long packet = in.readVarUInt();
        if (packet == ServerPacketType.PONG.code()) {
          break;
        }
        if (packet == ServerPacketType.PROGRESS.code()) {
          Progress.read(in, negotiatedRevision);
          continue;
        }
        if (packet == ServerPacketType.EXCEPTION.code()) {
          throw ServerError.read(in).toException();
        }
        throw new ChordProtocolException(
            "Expected Pong but received server packet type " + Long.toUnsignedString(packet));
      }
      state.transitionTo(ConnectionState.READY);
      LOG.trace("connection {} ping ok", id);
    } catch (RuntimeException e) {
      markBroken();
      // A ping has no effects to duplicate.
      classifyIfTransportLevel(e, RetryClass.SAFE_TO_RETRY);
      throw e;
    }
  }

  private void markBroken() {
    while (true) {
      ConnectionState current = state.state();
      if (current == ConnectionState.BROKEN || current == ConnectionState.CLOSED) {
        return;
      }
      if (state.transitionIfCurrent(current, ConnectionState.BROKEN)) {
        return;
      }
    }
  }

  /**
   * Closes the connection and its transport. Idempotent, callable from any thread and from any
   * state; closing is the required way to abandon a broken connection.
   */
  @Override
  public void close() {
    while (true) {
      ConnectionState current = state.state();
      if (current == ConnectionState.CLOSED) {
        return;
      }
      if (state.transitionIfCurrent(current, ConnectionState.CLOSED)) {
        break;
      }
    }
    transport.close();
    LOG.debug("connection {} closed", id);
  }
}
