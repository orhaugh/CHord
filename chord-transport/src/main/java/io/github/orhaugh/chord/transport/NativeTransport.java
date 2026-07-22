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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;

/**
 * A byte transport carrying the ClickHouse native protocol. The current implementation is plain
 * blocking TCP; a TLS transport implements the same contract in a later phase, which is why {@link
 * #isSecure()} is part of the interface: security policy decisions such as refusing to send a
 * password over plaintext key off it.
 *
 * <p>Transports expose raw unbuffered streams; buffering belongs to the protocol reader and writer
 * layered on top. Implementations are not required to be thread safe beyond allowing {@link
 * #close()} from another thread, which is the standard mechanism for aborting a blocked read.
 */
public interface NativeTransport extends AutoCloseable {

  /**
   * Returns the stream of bytes arriving from the server.
   *
   * @return the input stream
   */
  InputStream inputStream();

  /**
   * Returns the stream of bytes sent to the server.
   *
   * @return the output stream
   */
  OutputStream outputStream();

  /**
   * Returns the remote endpoint this transport is connected to.
   *
   * @return the remote address
   */
  SocketAddress remoteAddress();

  /**
   * Reports whether the transport is open.
   *
   * @return {@code true} until {@link #close()} or a fatal transport failure
   */
  boolean isOpen();

  /**
   * Reports whether the transport encrypts traffic. Plain TCP returns {@code false}; credentials
   * must not be sent over an insecure transport without an explicit opt in.
   *
   * @return {@code true} for encrypted transports
   */
  boolean isSecure();

  /**
   * Blocks until at least one byte is readable or the timeout elapses, consuming nothing. Used to
   * enforce deadlines at protocol packet boundaries without risking a timeout in the middle of a
   * value, which would leave the stream position unknowable.
   *
   * <p>The default conservatively reports readability without waiting, so callers fall back to
   * their blocking read under the transport's ordinary read timeout; implementations backed by a
   * socket override this with a real poll.
   *
   * @param timeoutMillis maximum time to wait, coerced to at least one millisecond
   * @return {@code true} when a byte is readable or the stream has ended; {@code false} on timeout
   */
  default boolean awaitReadable(int timeoutMillis) {
    return true;
  }

  /** Closes the transport, releasing the underlying resources. Idempotent and never throws. */
  @Override
  void close();
}
