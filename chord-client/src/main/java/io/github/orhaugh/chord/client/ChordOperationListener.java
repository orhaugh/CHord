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

import java.time.Duration;

/**
 * Receives one callback per completed operation on a connection, at the same points where the JDK
 * Flight Recorder events commit. Metrics facades (for example the Micrometer binder in {@code
 * chord-observability}) implement this to record operation timers without CHord depending on any
 * metrics library.
 *
 * <p>Callbacks run on the thread concluding the operation and must return quickly. A listener that
 * throws is logged and never disturbs the operation's outcome.
 *
 * <p>Outcome strings match the JFR events: queries conclude as {@code finished}, {@code
 * server_error}, {@code cancelled}, {@code cancelled_timeout} or {@code failed}; inserts as {@code
 * committed}, {@code aborted} or {@code failed}.
 */
public interface ChordOperationListener {

  /** The listener used when none is configured. */
  ChordOperationListener NOOP = new ChordOperationListener() {};

  /**
   * A connection attempt concluded.
   *
   * @param succeeded whether the handshake completed
   * @param duration wall time of the attempt
   */
  default void connectFinished(boolean succeeded, Duration duration) {}

  /**
   * A query concluded.
   *
   * @param outcome the JFR outcome string
   * @param duration wall time from request to stream conclusion
   * @param rowsRead rows the server reported read
   */
  default void queryFinished(String outcome, Duration duration, long rowsRead) {}

  /**
   * An insert concluded.
   *
   * @param outcome the JFR outcome string
   * @param duration wall time from request to conclusion
   * @param rowsSent rows the client sent
   */
  default void insertFinished(String outcome, Duration duration, long rowsSent) {}
}
