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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The settings flag word: important and custom bits, independently and together. */
class ServerSettingTest {

  @Test
  void flagBitsDecodeIndependently() {
    ServerSetting plain = new ServerSetting("max_threads", 0, "8");
    assertThat(plain.isImportant()).isFalse();
    assertThat(plain.isCustom()).isFalse();

    ServerSetting important = new ServerSetting("readonly", ServerSetting.FLAG_IMPORTANT, "1");
    assertThat(important.isImportant()).isTrue();
    assertThat(important.isCustom()).isFalse();

    ServerSetting custom = new ServerSetting("my_setting", ServerSetting.FLAG_CUSTOM, "x");
    assertThat(custom.isImportant()).isFalse();
    assertThat(custom.isCustom()).isTrue();

    ServerSetting both =
        new ServerSetting("tagged", ServerSetting.FLAG_IMPORTANT | ServerSetting.FLAG_CUSTOM, "y");
    assertThat(both.isImportant()).isTrue();
    assertThat(both.isCustom()).isTrue();

    // Unknown higher bits do not disturb the known ones.
    ServerSetting future = new ServerSetting("later", 0xF4L | ServerSetting.FLAG_IMPORTANT, "z");
    assertThat(future.isImportant()).isTrue();
    assertThat(future.isCustom()).isFalse();
  }

  @Test
  void componentsAreValidated() {
    assertThatThrownBy(() -> new ServerSetting(null, 0, "v"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("name");
    assertThatThrownBy(() -> new ServerSetting("n", 0, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("value");
  }
}
