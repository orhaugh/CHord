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
package io.github.orhaugh.chord.codec.block;

import java.time.ZoneId;
import java.util.Objects;

/**
 * Context threaded through block decoding.
 *
 * @param limits bounds applied to block dimensions
 * @param negotiatedRevision the negotiated protocol revision of the connection, which gates block
 *     level fields
 * @param serverTimezone the server session timezone, used for DateTime columns without an explicit
 *     column timezone
 */
public record DecodeContext(BlockLimits limits, long negotiatedRevision, ZoneId serverTimezone) {

  /** Validates components. */
  public DecodeContext {
    Objects.requireNonNull(limits, "limits");
    Objects.requireNonNull(serverTimezone, "serverTimezone");
  }
}
