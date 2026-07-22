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
package io.github.orhaugh.chord.jdbc;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A plain {@link DataSource} over the CHord driver.
 *
 * <p>Each {@code getConnection} opens a new native connection; pair this data source with a
 * connection pool (HikariCP or similar), or use CHord's native {@code ConnectionPool} directly for
 * non JDBC callers.
 */
public final class ChordDataSource implements DataSource {

  private final ChordDriver driver = new ChordDriver();
  private String url;
  private String user;
  private String password;
  private int loginTimeoutSeconds;

  /**
   * Sets the JDBC URL, in the same {@code jdbc:chord://} form the driver accepts.
   *
   * @param url the URL
   */
  public void setUrl(String url) {
    this.url = url;
  }

  /**
   * Returns the configured URL.
   *
   * @return the URL
   */
  public String getUrl() {
    return url;
  }

  /**
   * Sets the user, overriding any user in the URL.
   *
   * @param user the user name
   */
  public void setUser(String user) {
    this.user = user;
  }

  /**
   * Sets the password, overriding any password in the URL.
   *
   * @param password the password
   */
  public void setPassword(String password) {
    this.password = password;
  }

  @Override
  public Connection getConnection() throws SQLException {
    return getConnection(user, password);
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    if (url == null) {
      throw new SQLException("No URL configured on the data source", "08001");
    }
    Properties info = new Properties();
    if (username != null) {
      info.setProperty("user", username);
    }
    if (password != null) {
      info.setProperty("password", password);
    }
    Connection connection = driver.connect(url, info);
    if (connection == null) {
      throw new SQLException("The configured URL is not a CHord JDBC URL: " + url, "08001");
    }
    return connection;
  }

  @Override
  public PrintWriter getLogWriter() {
    return null;
  }

  @Override
  public void setLogWriter(PrintWriter out) {
    // CHord logs through SLF4J; the JDBC log writer is not used.
  }

  @Override
  public void setLoginTimeout(int seconds) {
    this.loginTimeoutSeconds = seconds;
  }

  @Override
  public int getLoginTimeout() {
    return loginTimeoutSeconds;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
        "java.util.logging is not used; CHord logs through SLF4J");
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName(), "HY000");
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return iface.isInstance(this);
  }
}
