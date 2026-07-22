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
package io.github.orhaugh.chord;

/**
 * A configured deadline elapsed: connect timeout, read timeout, or a request deadline.
 *
 * <p>A timeout mid exchange leaves the connection in an unknown protocol position, so the
 * connection is treated as broken and is never reused.
 */
public class ChordTimeoutException extends ChordException {

  private static final long serialVersionUID = 1L;

  @Override
  protected RetryClass defaultRetryClass() {
    return RetryClass.OUTCOME_UNKNOWN;
  }

  /**
   * Creates a timeout exception.
   *
   * @param message description of which deadline elapsed
   */
  public ChordTimeoutException(String message) {
    super(message);
  }

  /**
   * Creates a timeout exception with a cause.
   *
   * @param message description of which deadline elapsed
   * @param cause underlying cause, typically a {@link java.net.SocketTimeoutException}
   */
  public ChordTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }
}
