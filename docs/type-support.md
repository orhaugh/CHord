# Type support

The native columnar codec arrives in Phase 2; **no ClickHouse type is supported yet**. This table
exists from day one so support claims are always explicit. Statuses are `Yes` (implemented with
byte level and integration tests), `Planned (phase)`, or `No`.

Columns: Parse (type name parsing), Read (decode from native blocks), Write (encode into native
blocks), JDBC (mapping in the JDBC adapter), Object (row object mapping).

| Type | Parse | Read | Write | JDBC | Object | Notes |
|---|---|---|---|---|---|---|
| UInt8, UInt16, UInt32 | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | Widened lossless Java types |
| UInt64 | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | `long` with unsigned semantics plus `BigInteger` path |
| UInt128, UInt256 | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | `BigInteger` |
| Int8, Int16, Int32, Int64 | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | |
| Int128, Int256 | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | `BigInteger` |
| Float32, Float64 | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | |
| BFloat16 | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | `float` carrier |
| Bool | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | |
| String | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | `String` plus raw byte access |
| FixedString(N) | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | |
| Date, Date32 | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | `LocalDate` |
| DateTime, DateTime64 | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | `Instant`; timezone metadata retained separately |
| Time, Time64 | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | |
| Interval* | Planned (2) | Planned (2) | Planned (3) | No | Planned (7) | SELECT only upstream |
| UUID | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | `java.util.UUID` |
| IPv4, IPv6 | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | Dedicated value types |
| Enum8, Enum16 | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | Quoted label parsing |
| Decimal(P,S) family | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | `BigDecimal` |
| Nothing | Planned (2) | Planned (2) | No | No | No | |
| Nullable(T) | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | |
| Array(T) | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | Offset validation |
| Tuple(...), named tuples | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | Immutable typed tuple |
| Map(K, V) | Planned (2) | Planned (2) | Planned (3) | Planned (7) | Planned (7) | Wire order preserved |
| Nested | Planned (2) | Planned (2) | Planned (3) | No | Planned (7) | |
| LowCardinality(T) | Planned (2) | Planned (6) | Planned (6) | Planned (7) | Planned (7) | Dictionary index validation |
| SimpleAggregateFunction aliases | Planned (2) | Planned (6) | Planned (6) | No | No | Alias to inner type |
| AggregateFunction states | Planned (2) | Planned (6) | No | No | No | Opaque representation or explicit rejection |
| Geometry aliases (Point, Ring, Polygon, MultiPolygon, LineString, MultiLineString) | Planned (2) | Planned (6) | Planned (6) | No | No | Native representations of nested types |
| Variant | Planned (2) | Planned (6) | Planned (6) | No | Planned (8) | Discriminator validation |
| Dynamic | Planned (2) | Planned (6) | Planned (6) | No | Planned (8) | V2 serialisation |
| JSON | Planned (2) | Planned (6) | Planned (6) | No | Planned (8) | Documented serialisation modes only |
| QBit and newly introduced types | No | No | No | No | No | Explicit `UnsupportedClickHouseTypeException` planned rather than guessing |

Rules that govern this table:

- A cell only becomes `Yes` together with encode and decode round trip tests and integration
  coverage against real servers.
- CHord never guesses the size of an unsupported value and keeps reading; unsupported types fail
  explicitly at the type parsing layer.
- The type name parser (Phase 2) handles nested parentheses, quoted enum labels, escaped strings,
  named tuple elements, timezone parameters, precision and scale, whitespace variants and
  malformed input, with configurable nesting and size limits.
