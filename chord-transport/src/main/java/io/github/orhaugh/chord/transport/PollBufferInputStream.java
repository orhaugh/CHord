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

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * An input stream with a one byte poll buffer, for {@link NativeTransport#awaitReadable(int)}.
 *
 * <p>A readability poll consumes one byte from the underlying stream and stores it here; the next
 * read delivers that byte alone as a short read, never touching the underlying stream in the same
 * call. {@link java.io.PushbackInputStream} is deliberately not used: its array read delegates to
 * the underlying stream after delivering pushed back bytes, which blocks when the pushed back byte
 * was a complete protocol packet and no further bytes are coming.
 */
final class PollBufferInputStream extends InputStream {

  private final InputStream delegate;
  private int polled = -1;

  PollBufferInputStream(InputStream delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /** Stores a byte consumed by a readability poll, delivered by the next read. */
  void storePolledByte(int b) {
    if (polled >= 0) {
      throw new IllegalStateException("A polled byte is already buffered");
    }
    polled = b & 0xFF;
  }

  @Override
  public int read() throws IOException {
    if (polled >= 0) {
      int b = polled;
      polled = -1;
      return b;
    }
    return delegate.read();
  }

  @Override
  public int read(byte[] target, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, target.length);
    if (length == 0) {
      return 0;
    }
    if (polled >= 0) {
      target[offset] = (byte) polled;
      polled = -1;
      return 1;
    }
    return delegate.read(target, offset, length);
  }

  @Override
  public int available() throws IOException {
    return (polled >= 0 ? 1 : 0) + delegate.available();
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }
}
