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
package io.github.orhaugh.chord.transport;

import io.github.orhaugh.chord.ChordTimeoutException;
import io.github.orhaugh.chord.ChordTransportException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;

/**
 * Plain TCP transport over {@link Socket}.
 *
 * <p>Blocking socket I/O parks cleanly on virtual threads on every supported JDK, and {@code
 * SO_TIMEOUT} provides read deadlines, which {@code SocketChannel} blocking mode cannot. Reads that
 * exceed the configured read timeout surface as {@link SocketTimeoutException} from the streams;
 * the protocol layer maps and classifies them. Hard cancellation is performed by closing the
 * transport from another thread, which unblocks any pending read.
 */
public final class TcpTransport implements NativeTransport {

  private final Socket socket;
  private final InputStream in;
  private final OutputStream out;
  private final InetSocketAddress address;

  private TcpTransport(Socket socket, InetSocketAddress address, InputStream in, OutputStream out) {
    this.socket = socket;
    this.address = address;
    this.in = in;
    this.out = out;
  }

  /**
   * Opens a TCP connection.
   *
   * @param host server hostname or address
   * @param port server native protocol port, conventionally 9000 for plain TCP
   * @param options socket configuration
   * @return the connected transport
   */
  public static TcpTransport connect(String host, int port, TransportOptions options) {
    InetSocketAddress address = new InetSocketAddress(host, port);
    Socket socket = dial(address, options);
    try {
      return new TcpTransport(socket, address, socket.getInputStream(), socket.getOutputStream());
    } catch (IOException e) {
      closeQuietly(socket);
      throw new ChordTransportException("Failed to open streams to " + address, e);
    }
  }

  /**
   * Establishes a configured, connected plain socket. Shared with {@link TlsTransport}, which
   * layers the TLS engine on top of the same dial behaviour and deadlines.
   */
  static Socket dial(InetSocketAddress address, TransportOptions options) {
    if (address.isUnresolved()) {
      throw new ChordTransportException("Cannot resolve host " + address.getHostString());
    }
    Socket socket = new Socket();
    try {
      socket.setTcpNoDelay(options.tcpNoDelay());
      socket.setKeepAlive(options.keepAlive());
      if (options.receiveBufferSize() > 0) {
        socket.setReceiveBufferSize(options.receiveBufferSize());
      }
      if (options.sendBufferSize() > 0) {
        socket.setSendBufferSize(options.sendBufferSize());
      }
      socket.connect(address, (int) options.connectTimeout().toMillis());
      socket.setSoTimeout((int) options.readTimeout().toMillis());
      return socket;
    } catch (SocketTimeoutException e) {
      closeQuietly(socket);
      throw new ChordTimeoutException(
          "Connecting to " + address + " timed out after " + options.connectTimeout(), e);
    } catch (IOException e) {
      closeQuietly(socket);
      throw new ChordTransportException("Failed to connect to " + address, e);
    }
  }

  @Override
  public InputStream inputStream() {
    return in;
  }

  @Override
  public OutputStream outputStream() {
    return out;
  }

  @Override
  public SocketAddress remoteAddress() {
    return address;
  }

  @Override
  public boolean isOpen() {
    return !socket.isClosed();
  }

  @Override
  public boolean isSecure() {
    return false;
  }

  @Override
  public void close() {
    closeQuietly(socket);
  }

  static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException e) {
      // Nothing useful can be done with a close failure on an abandoned socket.
    }
  }
}
