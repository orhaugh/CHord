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
package io.github.orhaugh.chord.protocol.handshake;

import java.util.Objects;

/**
 * One setting entry from the server Hello settings block, carried in the STRINGS_WITH_FLAGS format
 * of {@code BaseSettings}: name, a VarUInt flags word, and the value rendered as a string. Servers
 * send their changed settings this way from protocol revision 54474 so clients can apply them to
 * the session.
 *
 * @param name setting name
 * @param flags raw flags word; see {@link #isImportant()} and {@link #isCustom()}
 * @param value setting value rendered as a string
 */
public record ServerSetting(String name, long flags, String value) {

  /** Flag bit marking a setting the receiver must not silently drop. */
  public static final long FLAG_IMPORTANT = 0x01;

  /** Flag bit marking a custom (user defined) setting. */
  public static final long FLAG_CUSTOM = 0x02;

  /**
   * Validates components.
   *
   * @param name setting name
   * @param flags raw flags word
   * @param value setting value rendered as a string
   */
  public ServerSetting {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(value, "value");
  }

  /**
   * Reports whether the important flag is set, meaning the setting must not be silently dropped by
   * an older peer.
   *
   * @return {@code true} when the important bit is set
   */
  public boolean isImportant() {
    return (flags & FLAG_IMPORTANT) != 0;
  }

  /**
   * Reports whether the custom flag is set, marking a user defined setting.
   *
   * @return {@code true} when the custom bit is set
   */
  public boolean isCustom() {
    return (flags & FLAG_CUSTOM) != 0;
  }
}
