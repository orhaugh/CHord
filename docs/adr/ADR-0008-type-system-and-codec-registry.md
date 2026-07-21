# ADR-0008: Type system and codec registry

Status: Accepted (2026-07-21), implementation lands in Phase 2

## Context

ClickHouse types are recursive (`Map(String, Array(Nullable(DateTime64(3, 'UTC'))))`) and their
textual names are the wire's schema language. Representing types as strings sprinkled through
the code makes correct nesting, equality and codec dispatch impossible to guarantee.

## Decision

Types are modelled as a sealed recursive hierarchy in `chord-codec`:

```java
sealed interface ClickHouseType permits
    PrimitiveType, DecimalType, DateTimeType, FixedStringType, NullableType,
    ArrayType, TupleType, MapType, LowCardinalityType, EnumType,
    VariantType, DynamicType, JsonType, AggregateFunctionType { }
```

A single parser turns type names into this model, handling nested parentheses, quoted enum
labels, escaped strings, named tuple elements, timezone parameters, precision and scale,
whitespace variants and malformed input, with configurable nesting depth and length limits.
Column codecs are resolved from the type model through a codec registry; there is exactly one
codec per type shape, and an unknown or unsupported type resolves to an explicit
`UnsupportedClickHouseTypeException`, never to a guess about value sizes. Java value mappings are
lossless (`BigInteger` for the 128 and 256 bit integers, `BigDecimal` for decimals, `Instant`
plus retained zone metadata for DateTime, dedicated value types for IP addresses).

## Alternatives

- Strings plus a switch: rejected; unverifiable and un-extensible.
- Exposing codec internals as public API: rejected; the type model is public, codec wiring stays
  internal so implementations can change.

## Consequences

- Phase 2 starts with the parser plus golden tests and property based round trips before any
  column codec lands.
- The sealed hierarchy grows deliberately; new upstream types arrive as explicit members or as
  explicit rejections, keeping the type support table truthful.
