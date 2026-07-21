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
package io.github.orhaugh.chord.codec.type;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhaugh.chord.codec.type.ClickHouseType.ArrayType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.EnumEntry;
import io.github.orhaugh.chord.codec.type.ClickHouseType.EnumType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.LowCardinalityType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.MapType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.NullableType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.TupleElement;
import io.github.orhaugh.chord.codec.type.ClickHouseType.TupleType;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Rendering a generated type and parsing it back must be the identity. */
class TypeRoundTripPropertyTest {

  @Property(tries = 500)
  void renderedTypesParseBackToThemselves(@ForAll("types") ClickHouseType type) {
    String rendered = type.name();
    ClickHouseType reparsed = TypeParser.parse(rendered);
    assertThat(reparsed).isEqualTo(type);
    assertThat(reparsed.name()).isEqualTo(rendered);
  }

  @Provide
  Arbitrary<ClickHouseType> types() {
    return typesOfDepth(3);
  }

  private Arbitrary<ClickHouseType> typesOfDepth(int depth) {
    Arbitrary<ClickHouseType> leaves =
        Arbitraries.oneOf(
            Arbitraries.of(8, 16, 32, 64, 128, 256)
                .flatMap(
                    bits ->
                        Arbitraries.of(true, false)
                            .map(signed -> new ClickHouseType.IntegerType(bits, signed))),
            Arbitraries.of(32, 64).map(ClickHouseType.FloatType::new),
            Arbitraries.just(new ClickHouseType.StringType()),
            Arbitraries.just(new ClickHouseType.BoolType()),
            Arbitraries.just(new ClickHouseType.DateType()),
            Arbitraries.just(new ClickHouseType.UuidType()),
            Arbitraries.integers().between(1, 64).map(ClickHouseType.FixedStringType::new),
            Arbitraries.integers()
                .between(0, 9)
                .flatMap(
                    p ->
                        Arbitraries.of("UTC", "Europe/London", "Asia/Tokyo")
                            .map(tz -> new ClickHouseType.DateTime64Type(p, Optional.of(tz)))),
            Arbitraries.integers()
                .between(1, 76)
                .flatMap(
                    p ->
                        Arbitraries.integers()
                            .between(0, p)
                            .map(s -> new ClickHouseType.DecimalType(p, s))),
            enums());
    if (depth == 0) {
      return leaves;
    }
    Arbitrary<ClickHouseType> nested = typesOfDepth(depth - 1);
    return Arbitraries.oneOf(
        leaves,
        nested.map(NullableType::new).filter(t -> !(t.inner() instanceof NullableType)),
        nested.map(ArrayType::new),
        nested.map(LowCardinalityType::new).filter(t -> !(t.inner() instanceof NullableType)),
        Arbitraries.of("a", "b_c", "value")
            .flatMap(
                n -> nested.map(t -> new TupleType(List.of(new TupleElement(Optional.of(n), t))))),
        nested.tuple2().map(pair -> new MapType(pair.get1(), pair.get2())));
  }

  private Arbitrary<ClickHouseType> enums() {
    return Arbitraries.strings()
        .withCharRange('a', 'z')
        .ofMinLength(1)
        .ofMaxLength(6)
        .list()
        .ofMinSize(1)
        .ofMaxSize(4)
        .uniqueElements()
        .map(
            labels -> {
              var entries = new java.util.ArrayList<EnumEntry>();
              for (int i = 0; i < labels.size(); i++) {
                entries.add(new EnumEntry(labels.get(i), i - 1));
              }
              return new EnumType(8, entries);
            });
  }
}
