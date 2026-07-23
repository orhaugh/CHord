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
package io.github.orhaugh.chord.codec.column;

import io.github.orhaugh.chord.ChordTypeException;
import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Resolves timezone names arriving in wire type declarations. Type names travel inside blocks, so a
 * corrupt or hostile name must surface as a typed decode failure, never a raw {@link
 * DateTimeException}.
 */
final class Timezones {

  private Timezones() {}

  static ZoneId parse(String name) {
    try {
      return ZoneId.of(name);
    } catch (DateTimeException e) {
      throw new ChordTypeException("Malformed timezone in column type: \"" + name + "\"", e);
    }
  }
}
