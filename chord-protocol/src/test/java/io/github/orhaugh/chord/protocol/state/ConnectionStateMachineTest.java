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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Legal and illegal transitions of the connection state machine. */
class ConnectionStateMachineTest {

  @Test
  void allowsTheHappyPathThroughPing() {
    ConnectionStateMachine machine = new ConnectionStateMachine();
    assertThat(machine.state()).isEqualTo(ConnectionState.NEW);
    machine.transitionTo(ConnectionState.HANDSHAKING);
    machine.transitionTo(ConnectionState.READY);
    machine.transitionTo(ConnectionState.PINGING);
    machine.transitionTo(ConnectionState.READY);
    machine.transitionTo(ConnectionState.CLOSED);
    assertThat(machine.state()).isEqualTo(ConnectionState.CLOSED);
  }

  @Test
  void allowsTheQueryCycle() {
    ConnectionStateMachine machine = new ConnectionStateMachine();
    machine.transitionTo(ConnectionState.HANDSHAKING);
    machine.transitionTo(ConnectionState.READY);
    machine.transitionTo(ConnectionState.WRITING_QUERY);
    machine.transitionTo(ConnectionState.READING_RESPONSE);
    machine.transitionTo(ConnectionState.WRITING_INSERT);
    machine.transitionTo(ConnectionState.READING_RESPONSE);
    machine.transitionTo(ConnectionState.READY);
    assertThat(machine.state()).isEqualTo(ConnectionState.READY);
  }

  @Test
  void rejectsSkippingTheHandshake() {
    ConnectionStateMachine machine = new ConnectionStateMachine();
    assertThatThrownBy(() -> machine.transitionTo(ConnectionState.READY))
        .isInstanceOf(IllegalStateTransitionException.class)
        .hasMessageContaining("NEW -> READY");
  }

  @Test
  void brokenOnlyAllowsClosing() {
    ConnectionStateMachine machine = new ConnectionStateMachine();
    machine.transitionTo(ConnectionState.HANDSHAKING);
    machine.transitionTo(ConnectionState.BROKEN);
    assertThatThrownBy(() -> machine.transitionTo(ConnectionState.READY))
        .isInstanceOf(IllegalStateTransitionException.class);
    machine.transitionTo(ConnectionState.CLOSED);
    assertThat(machine.state()).isEqualTo(ConnectionState.CLOSED);
  }

  @Test
  void closedIsTerminal() {
    ConnectionStateMachine machine = new ConnectionStateMachine();
    machine.transitionTo(ConnectionState.CLOSED);
    for (ConnectionState target : ConnectionState.values()) {
      assertThatThrownBy(() -> machine.transitionTo(target))
          .isInstanceOf(IllegalStateTransitionException.class);
    }
  }

  @Test
  void requireNamesTheOperationInFailures() {
    ConnectionStateMachine machine = new ConnectionStateMachine();
    assertThatThrownBy(() -> machine.require(ConnectionState.READY, "ping"))
        .isInstanceOf(IllegalStateTransitionException.class)
        .hasMessageContaining("ping")
        .hasMessageContaining("NEW");
  }

  @Test
  void conditionalTransitionReportsWhetherItWon() {
    ConnectionStateMachine machine = new ConnectionStateMachine();
    machine.transitionTo(ConnectionState.HANDSHAKING);
    machine.transitionTo(ConnectionState.READY);
    assertThat(machine.transitionIfCurrent(ConnectionState.READY, ConnectionState.PINGING))
        .isTrue();
    assertThat(machine.transitionIfCurrent(ConnectionState.READY, ConnectionState.PINGING))
        .isFalse();
  }
}
