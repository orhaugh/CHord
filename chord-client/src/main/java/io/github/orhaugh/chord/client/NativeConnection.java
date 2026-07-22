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
import io.github.orhaugh.chord.annotations.Experimental;
import io.github.orhaugh.chord.codec.block.BlockLimits;
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
import io.github.orhaugh.chord.transport.NativeTransport;
import io.github.orhaugh.chord.transport.TcpTransport;
import io.github.orhaugh.chord.transport.TlsTransport;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.time.ZoneId;
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
  private final WireWriter out;
  private final ServerHello serverHello;
  private final long negotiatedRevision;
  private final ConnectionStateMachine state;
  private final ConnectionOptions options;
  private volatile ZoneId sessionTimezone;

  private NativeConnection(
      long id,
      NativeTransport transport,
      WireReader in,
      WireWriter out,
      ServerHello serverHello,
      long negotiatedRevision,
      ConnectionStateMachine state,
      ConnectionOptions options) {
    this.id = id;
    this.transport = transport;
    this.in = in;
    this.out = out;
    this.serverHello = serverHello;
    this.negotiatedRevision = negotiatedRevision;
    this.state = state;
    this.options = options;
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
    if (options.tls().isPresent()) {
      transport =
          TlsTransport.connect(
              options.host(), options.port(), options.transportOptions(), options.tls().get());
    } else {
      requirePlaintextPasswordOptIn(options, false);
      transport = TcpTransport.connect(options.host(), options.port(), options.transportOptions());
    }
    return open(options, transport);
  }

  /**
   * Performs the handshake over an already connected transport. Package access for tests and future
   * transports; the transport is owned by the returned connection, and is closed here on any
   * handshake failure.
   */
  static NativeConnection open(ConnectionOptions options, NativeTransport transport) {
    long id = IDS.incrementAndGet();
    ConnectionStateMachine state = new ConnectionStateMachine();
    char[] password = options.passwordChars();
    try {
      requirePlaintextPasswordOptIn(options, transport.isSecure());
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
              options.username());
      HelloCodec.writeClientHello(out, clientHello, password);
      out.flush();

      ServerHello serverHello = HelloCodec.readServerHello(in, options.advertisedRevision());
      long negotiated =
          ProtocolRevisions.negotiate(options.advertisedRevision(), serverHello.serverRevision());

      negotiatePlainFraming(serverHello, negotiated);

      if (ProtocolFeature.ADDENDUM.enabledFor(negotiated)) {
        HelloCodec.writeAddendum(
            out,
            negotiated,
            options.quotaKey(),
            ChunkedProtocolMode.NOTCHUNKED,
            ChunkedProtocolMode.NOTCHUNKED);
        out.flush();
      }

      NativeConnection connection =
          new NativeConnection(id, transport, in, out, serverHello, negotiated, state, options);
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
      transport.close();
      throw e;
    } finally {
      ConnectionOptions.wipe(password);
    }
  }

  /**
   * The ClickHouse native protocol sends passwords verbatim inside the handshake, so a non empty
   * password over a plaintext transport is refused unless explicitly permitted.
   */
  private static void requirePlaintextPasswordOptIn(ConnectionOptions options, boolean secure) {
    if (options.hasPassword() && !secure && !options.allowPlaintextPassword()) {
      throw new ChordConfigurationException(
          "Refusing to send a password over a plaintext connection. Use TLS, or explicitly opt"
              + " in with allowPlaintextPassword(true) if this network is secured by other"
              + " means.");
    }
  }

  /**
   * Resolves the chunked framing negotiation to plain framing. CHord requests notchunked for both
   * channels until chunked framing lands in Phase 4; a server that strictly requires chunked
   * framing is refused with a clear error rather than desynchronised.
   */
  private static void negotiatePlainFraming(ServerHello serverHello, long negotiated) {
    if (!ProtocolFeature.CHUNKED_PACKETS.enabledFor(negotiated)) {
      return;
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
    // The client send channel pairs with the server receive capability and vice versa.
    boolean sendChunked =
        ChunkedProtocolMode.resolveChunked(serverReceive, ChunkedProtocolMode.NOTCHUNKED, "send");
    boolean receiveChunked =
        ChunkedProtocolMode.resolveChunked(serverSend, ChunkedProtocolMode.NOTCHUNKED, "recv");
    if (sendChunked || receiveChunked) {
      throw new ChordProtocolException(
          "Chunked framing resolved as required, but CHord does not implement it yet");
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
      QueryCodec.writeQuery(out, request, options, negotiatedRevision, osUser(), localHostname());
      out.flush();
      state.transitionTo(ConnectionState.READING_RESPONSE);
      LOG.debug("connection {} query {} sent", id, request.queryId());
      return new NativeQueryResult(this, in, request.queryId());
    } catch (RuntimeException e) {
      // A server Exception packet received while priming the result concludes the stream
      // cleanly and has already returned the connection to READY; only failures that leave
      // the exchange unfinished poison the connection.
      ConnectionState current = state.state();
      if (current == ConnectionState.WRITING_QUERY || current == ConnectionState.READING_RESPONSE) {
        markBroken();
      }
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

  /** Returns the block decode limits configured for this connection. */
  BlockLimits blockLimits() {
    return options.blockLimits();
  }

  /** Returns the current session timezone, as updated by TimezoneUpdate packets. */
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
      QueryCodec.writeQuery(out, request, options, negotiatedRevision, osUser(), localHostname());
      out.flush();
      state.transitionTo(ConnectionState.READING_RESPONSE);
      LOG.debug("connection {} insert {} sent", id, request.queryId());
      return new NativeInsertStream(this, in, out, request.queryId());
    } catch (RuntimeException e) {
      ConnectionState current = state.state();
      if (current == ConnectionState.WRITING_QUERY || current == ConnectionState.READING_RESPONSE) {
        markBroken();
      }
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
