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

import io.github.orhaugh.chord.annotations.Experimental;
import io.github.orhaugh.chord.protocol.handshake.ServerHello;
import io.github.orhaugh.chord.protocol.state.ConnectionState;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A connection leased from a {@link ConnectionPool}.
 *
 * <p>Closing the lease returns the connection to the pool when it is still {@link
 * ConnectionState#READY}; a connection left mid exchange or broken is closed and discarded instead,
 * so the pool never hands out a connection in an unknown protocol position. The lease is single
 * use: after {@code close()} every operation fails.
 *
 * <p>Like the underlying connection, a lease runs one exchange at a time on one thread.
 */
@Experimental
public final class PooledConnection implements AutoCloseable {

  private final ConnectionPool pool;
  private final NativeConnection connection;
  private final AtomicBoolean released = new AtomicBoolean();

  PooledConnection(ConnectionPool pool, NativeConnection connection) {
    this.pool = pool;
    this.connection = connection;
  }

  /**
   * Executes a query on the leased connection.
   *
   * @param request the query to execute
   * @return the streaming result, which must be closed before the lease is
   * @see NativeConnection#query(QueryRequest)
   */
  public QueryResult query(QueryRequest request) {
    return connection().query(request);
  }

  /**
   * Begins a native INSERT on the leased connection.
   *
   * @param request the INSERT statement
   * @return the insert stream, which must be finished or closed before the lease is
   * @see NativeConnection#insert(QueryRequest)
   */
  public InsertStream insert(QueryRequest request) {
    return connection().insert(request);
  }

  /** Verifies the leased connection is alive and in protocol sync. */
  public void ping() {
    connection().ping();
  }

  /**
   * Returns the decoded server half of the handshake.
   *
   * @return the server hello
   */
  public ServerHello serverHello() {
    return connection().serverHello();
  }

  /**
   * Returns the underlying connection for the duration of the lease.
   *
   * @return the leased native connection
   */
  public NativeConnection connection() {
    if (released.get()) {
      throw new IllegalStateException("The lease has been closed");
    }
    return connection;
  }

  /** Returns the connection to the pool, or discards it when it is not cleanly reusable. */
  @Override
  public void close() {
    if (released.compareAndSet(false, true)) {
      pool.release(this, connection);
    }
  }

  boolean isReleased() {
    return released.get();
  }
}
