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

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * JDK Flight Recorder events emitted by the client. Events cost nothing measurable while no
 * recording is active, so they are emitted unconditionally; queries never carry SQL text into
 * recordings, only query ids.
 */
final class JfrEvents {

  private JfrEvents() {}

  /** A connection attempt: dial, handshake and authentication. */
  @Name("io.github.orhaugh.chord.Connect")
  @Label("CHord Connect")
  @Category("CHord")
  @Description("Opening a native protocol connection: dial, handshake and authentication")
  static final class ConnectEvent extends Event {
    @Label("Host")
    String host;

    @Label("Port")
    int port;

    @Label("TLS")
    boolean secure;

    @Label("Negotiated revision")
    long negotiatedRevision;

    @Label("Succeeded")
    boolean succeeded;
  }

  /** One query exchange, from send to stream conclusion. */
  @Name("io.github.orhaugh.chord.Query")
  @Label("CHord Query")
  @Category("CHord")
  @Description("A streaming SELECT exchange from Query packet to stream conclusion")
  static final class QueryEvent extends Event {
    @Label("Query id")
    String queryId;

    @Label("Rows read")
    long rowsRead;

    @Label("Bytes read")
    long bytesRead;

    @Label("Outcome")
    @Description("finished, server_error, cancelled_timeout or failed")
    String outcome;
  }

  /** One INSERT exchange, from send to commit or abort. */
  @Name("io.github.orhaugh.chord.Insert")
  @Label("CHord Insert")
  @Category("CHord")
  @Description("A native INSERT exchange from Query packet to commit or abort")
  static final class InsertEvent extends Event {
    @Label("Query id")
    String queryId;

    @Label("Rows sent")
    long rowsSent;

    @Label("Blocks sent")
    long blocksSent;

    @Label("Outcome")
    @Description("committed, server_error, aborted or failed")
    String outcome;
  }

  /** One pool acquire, including the wait for a permit. */
  @Name("io.github.orhaugh.chord.PoolAcquire")
  @Label("CHord Pool Acquire")
  @Category("CHord")
  @Description("Borrowing a pooled connection, including the wait for capacity")
  static final class PoolAcquireEvent extends Event {
    @Label("Opened new connection")
    boolean openedNewConnection;

    @Label("Succeeded")
    boolean succeeded;
  }
}
