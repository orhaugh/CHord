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
package io.github.orhaugh.chord.protocol.state;

import io.github.orhaugh.chord.ChordException;

/**
 * An operation was attempted in a connection state that does not permit it, or a state transition
 * was requested that the protocol does not allow. This always indicates a bug in the caller or in
 * CHord itself, never a server fault.
 */
public class IllegalStateTransitionException extends ChordException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message description of the rejected transition or operation
   */
  public IllegalStateTransitionException(String message) {
    super(message);
  }
}
