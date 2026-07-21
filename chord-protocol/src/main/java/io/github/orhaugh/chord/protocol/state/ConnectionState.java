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

/**
 * States of a native protocol connection. A ClickHouse native connection is stateful and runs one
 * protocol exchange at a time; these states model that fact explicitly so an out of order operation
 * fails immediately instead of desynchronising the stream.
 */
public enum ConnectionState {

  /** Created, no bytes exchanged. */
  NEW,
  /** Hello sent, waiting for or processing the server response and addendum. */
  HANDSHAKING,
  /** Idle and able to start a new exchange. */
  READY,
  /** Ping sent, waiting for Pong. */
  PINGING,
  /** Sending a Query packet and any external tables. */
  WRITING_QUERY,
  /** Consuming the server response stream. */
  READING_RESPONSE,
  /** Streaming INSERT data blocks. */
  WRITING_INSERT,
  /** Cancel requested; draining the server response. */
  CANCELLING,
  /** The protocol position is unknown or violated. The connection must never be reused. */
  BROKEN,
  /** Closed. Terminal. */
  CLOSED
}
