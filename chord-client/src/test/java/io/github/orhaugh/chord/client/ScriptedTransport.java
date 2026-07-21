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

import io.github.orhaugh.chord.transport.NativeTransport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * In memory transport replaying a fixed server byte script, capturing everything the client writes.
 * Lets handshake and ping logic be tested byte for byte without a server.
 */
final class ScriptedTransport implements NativeTransport {

  private final ByteArrayInputStream serverScript;
  private final ByteArrayOutputStream clientBytes = new ByteArrayOutputStream();
  private final boolean secure;
  private volatile boolean open = true;

  ScriptedTransport(byte[] serverScript) {
    this(serverScript, false);
  }

  ScriptedTransport(byte[] serverScript, boolean secure) {
    this.serverScript = new ByteArrayInputStream(serverScript);
    this.secure = secure;
  }

  byte[] clientBytes() {
    return clientBytes.toByteArray();
  }

  @Override
  public InputStream inputStream() {
    return serverScript;
  }

  @Override
  public OutputStream outputStream() {
    return clientBytes;
  }

  @Override
  public SocketAddress remoteAddress() {
    return InetSocketAddress.createUnresolved("scripted", 9000);
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public boolean isSecure() {
    return secure;
  }

  @Override
  public void close() {
    open = false;
  }
}
