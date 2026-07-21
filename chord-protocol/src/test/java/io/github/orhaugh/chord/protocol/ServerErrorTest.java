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
package io.github.orhaugh.chord.protocol;

import static io.github.orhaugh.chord.protocol.wire.WireTestUtil.bytes;
import static io.github.orhaugh.chord.protocol.wire.WireTestUtil.reader;
import static io.github.orhaugh.chord.protocol.wire.WireTestUtil.written;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordAuthenticationException;
import io.github.orhaugh.chord.ChordException;
import io.github.orhaugh.chord.ChordProtocolException;
import io.github.orhaugh.chord.ChordServerException;
import io.github.orhaugh.chord.protocol.wire.WireLimits;
import org.junit.jupiter.api.Test;

/**
 * Decoding of Exception packet bodies, mirroring {@code writeException} in {@code
 * src/IO/WriteHelpers.cpp}.
 */
class ServerErrorTest {

  @Test
  void decodesFlatExceptionFromExactBytes() {
    byte[] body =
        bytes(
            // Code 516 as little endian Int32.
            0x04,
            0x02,
            0x00,
            0x00,
            // Name "DB::Exception", length 13.
            0x0D,
            'D',
            'B',
            ':',
            ':',
            'E',
            'x',
            'c',
            'e',
            'p',
            't',
            'i',
            'o',
            'n',
            // Message "denied", length 6.
            0x06,
            'd',
            'e',
            'n',
            'i',
            'e',
            'd',
            // Empty stack trace.
            0x00,
            // No nested exception.
            0x00);

    ServerError error = ServerError.read(reader(body));

    assertThat(error.code()).isEqualTo(516);
    assertThat(error.name()).isEqualTo("DB::Exception");
    assertThat(error.message()).isEqualTo("denied");
    assertThat(error.stackTrace()).isEmpty();
    assertThat(error.nested()).isEmpty();
  }

  @Test
  void decodesNestedExceptionChain() {
    byte[] body =
        written(
            w -> {
              w.writeInt32Le(1000);
              w.writeString("DB::Exception");
              w.writeString("outer failure");
              w.writeString("outer stack");
              w.writeBool(true);
              w.writeInt32Le(516);
              w.writeString("DB::Exception");
              w.writeString("inner failure");
              w.writeString("");
              w.writeBool(false);
            });

    ServerError error = ServerError.read(reader(body));

    assertThat(error.code()).isEqualTo(1000);
    assertThat(error.message()).isEqualTo("outer failure");
    assertThat(error.nested()).isPresent();
    assertThat(error.nested().orElseThrow().code()).isEqualTo(516);
  }

  @Test
  void boundsNestedExceptionDepth() {
    WireLimits limits = new WireLimits(1024, 2, 100);
    byte[] body =
        written(
            w -> {
              for (int i = 0; i < 3; i++) {
                w.writeInt32Le(i);
                w.writeString("DB::Exception");
                w.writeString("level " + i);
                w.writeString("");
                w.writeBool(i < 2);
              }
            });

    assertThatThrownBy(() -> ServerError.read(reader(body, limits)))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("nesting depth");
  }

  @Test
  void mapsAuthenticationCodesToAuthenticationException() {
    for (int code : new int[] {192, 193, 194, 516}) {
      ServerError error =
          new ServerError(code, "DB::Exception", "no entry", "", java.util.Optional.empty());
      ChordException exception = error.toException();
      assertThat(exception).isInstanceOf(ChordAuthenticationException.class);
      assertThat(((ChordAuthenticationException) exception).code()).isEqualTo(code);
    }
  }

  @Test
  void mapsOtherCodesToServerExceptionWithNestedCause() {
    ServerError inner =
        new ServerError(60, "DB::Exception", "no table", "", java.util.Optional.empty());
    ServerError outer =
        new ServerError(1000, "DB::Exception", "wrapper", "trace", java.util.Optional.of(inner));

    ChordException exception = outer.toException();

    assertThat(exception).isInstanceOf(ChordServerException.class);
    ChordServerException server = (ChordServerException) exception;
    assertThat(server.code()).isEqualTo(1000);
    assertThat(server.serverStackTrace()).isEqualTo("trace");
    assertThat(server.getCause()).isInstanceOf(ChordServerException.class);
    assertThat(((ChordServerException) server.getCause()).code()).isEqualTo(60);
  }
}
