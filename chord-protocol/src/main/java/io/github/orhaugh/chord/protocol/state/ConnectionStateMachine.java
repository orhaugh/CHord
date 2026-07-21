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

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enforces the legal transitions between {@link ConnectionState} values. Invalid transitions fail
 * immediately with {@link IllegalStateTransitionException}; nothing ever moves out of {@link
 * ConnectionState#CLOSED}, and the only exit from {@link ConnectionState#BROKEN} is closing.
 *
 * <p>Thread safe: transitions are atomic, so a close racing a protocol exchange resolves to exactly
 * one winner.
 */
public final class ConnectionStateMachine {

  private static final Map<ConnectionState, Set<ConnectionState>> ALLOWED = allowedTransitions();

  private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.NEW);

  private static Map<ConnectionState, Set<ConnectionState>> allowedTransitions() {
    Map<ConnectionState, Set<ConnectionState>> allowed = new EnumMap<>(ConnectionState.class);
    allowed.put(
        ConnectionState.NEW, EnumSet.of(ConnectionState.HANDSHAKING, ConnectionState.CLOSED));
    allowed.put(
        ConnectionState.HANDSHAKING,
        EnumSet.of(ConnectionState.READY, ConnectionState.BROKEN, ConnectionState.CLOSED));
    allowed.put(
        ConnectionState.READY,
        EnumSet.of(
            ConnectionState.PINGING,
            ConnectionState.WRITING_QUERY,
            ConnectionState.BROKEN,
            ConnectionState.CLOSED));
    allowed.put(
        ConnectionState.PINGING,
        EnumSet.of(ConnectionState.READY, ConnectionState.BROKEN, ConnectionState.CLOSED));
    allowed.put(
        ConnectionState.WRITING_QUERY,
        EnumSet.of(
            ConnectionState.READING_RESPONSE,
            ConnectionState.CANCELLING,
            ConnectionState.BROKEN,
            ConnectionState.CLOSED));
    allowed.put(
        ConnectionState.READING_RESPONSE,
        EnumSet.of(
            ConnectionState.READY,
            ConnectionState.WRITING_INSERT,
            ConnectionState.CANCELLING,
            ConnectionState.BROKEN,
            ConnectionState.CLOSED));
    allowed.put(
        ConnectionState.WRITING_INSERT,
        EnumSet.of(
            ConnectionState.READING_RESPONSE,
            ConnectionState.CANCELLING,
            ConnectionState.BROKEN,
            ConnectionState.CLOSED));
    allowed.put(
        ConnectionState.CANCELLING,
        EnumSet.of(ConnectionState.READY, ConnectionState.BROKEN, ConnectionState.CLOSED));
    allowed.put(ConnectionState.BROKEN, EnumSet.of(ConnectionState.CLOSED));
    allowed.put(ConnectionState.CLOSED, EnumSet.noneOf(ConnectionState.class));
    return allowed;
  }

  /**
   * Returns the current state.
   *
   * @return the current state
   */
  public ConnectionState state() {
    return state.get();
  }

  /**
   * Moves to the target state, failing if the transition is not permitted from the current state.
   *
   * @param target state to move to
   */
  public void transitionTo(ConnectionState target) {
    while (true) {
      ConnectionState current = state.get();
      if (!ALLOWED.get(current).contains(target)) {
        throw new IllegalStateTransitionException(
            "Illegal connection state transition " + current + " -> " + target);
      }
      if (state.compareAndSet(current, target)) {
        return;
      }
    }
  }

  /**
   * Asserts the connection is in the expected state before an operation.
   *
   * @param expected the state the operation requires
   * @param operation operation name for the error message
   */
  public void require(ConnectionState expected, String operation) {
    ConnectionState current = state.get();
    if (current != expected) {
      throw new IllegalStateTransitionException(
          operation
              + " requires connection state "
              + expected
              + " but the connection is "
              + current);
    }
  }

  /**
   * Moves to the target state only when currently in the expected state; otherwise reports whether
   * the move happened. Useful for close paths racing an active exchange.
   *
   * @param expected the state to move from
   * @param target the state to move to
   * @return {@code true} when the transition happened
   */
  public boolean transitionIfCurrent(ConnectionState expected, ConnectionState target) {
    if (!ALLOWED.get(expected).contains(target)) {
      throw new IllegalStateTransitionException(
          "Illegal connection state transition " + expected + " -> " + target);
    }
    return state.compareAndSet(expected, target);
  }
}
