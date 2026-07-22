# Type support

Statuses are `Yes` (implemented with byte level and integration tests against real servers),
`Planned (phase)`, `Unscheduled`, or `No`. Reading covers uncompressed SELECT results and writing covers native
INSERT blocks; both directions are round trip tested.

Columns: Parse (type name parsing), Read (decode from native blocks), Write (encode into native
blocks), JDBC (mapping in the JDBC adapter), Object (row object mapping).

| Type | Parse | Read | Write | JDBC | Object | Notes |
|---|---|---|---|---|---|---|
| UInt8, UInt16, UInt32 | Yes | Yes | Yes | Yes | Unscheduled | Widened lossless Java types |
| UInt64 | Yes | Yes | Yes | Yes | Unscheduled | `long` with unsigned semantics plus `BigInteger` path |
| UInt128, UInt256 | Yes | Yes | Yes | Yes | Unscheduled | `BigInteger` |
| Int8, Int16, Int32, Int64 | Yes | Yes | Yes | Yes | Unscheduled | |
| Int128, Int256 | Yes | Yes | Yes | Yes | Unscheduled | `BigInteger` |
| Float32, Float64 | Yes | Yes | Yes | Yes | Unscheduled | |
| BFloat16 | Yes | Yes | Yes | Yes | Unscheduled | `float` carrier |
| Bool | Yes | Yes | Yes | Yes | Unscheduled | |
| String | Yes | Yes | Yes | Yes | Unscheduled | `String` plus raw byte access |
| FixedString(N) | Yes | Yes | Yes | Yes | Unscheduled | |
| Date, Date32 | Yes | Yes | Yes | Yes | Unscheduled | `LocalDate` |
| DateTime, DateTime64 | Yes | Yes | Yes | Yes | Unscheduled | `Instant`; timezone metadata retained separately |
| Time, Time64 | No (explicit rejection) | No | No | No | No | Recently introduced upstream; unscheduled until demand exists |
| Interval* | Yes | Yes | Yes | No | Unscheduled | SELECT only upstream |
| UUID | Yes | Yes | Yes | Yes | Unscheduled | `java.util.UUID` |
| IPv4, IPv6 | Yes | Yes | Yes | Yes | Unscheduled | Dedicated value types |
| Enum8, Enum16 | Yes | Yes | Yes | Yes | Unscheduled | Quoted label parsing |
| Decimal(P,S) family | Yes | Yes | Yes | Yes | Unscheduled | `BigDecimal` |
| Nothing | Yes | Yes | No | No | No | |
| Nullable(T) | Yes | Yes | Yes | Yes | Unscheduled | |
| Array(T) | Yes | Yes | Yes | Yes | Unscheduled | Offset validation |
| Tuple(...), named tuples | Yes | Yes | Yes | Yes | Unscheduled | Immutable typed tuple |
| Map(K, V) | Yes | Yes | Yes | Yes | Unscheduled | Wire order preserved |
| Nested | Yes | No (flattened by the server in results) | No (insert via the flattened Array subcolumns) | No | Unscheduled | |
| LowCardinality(T) | Yes | Yes | Yes | Yes | Unscheduled | Dictionary and index validation; NULL in slot zero for nullable inner types |
| SimpleAggregateFunction aliases | Yes | Yes (as the inner type) | Yes (as the inner type) | No | No | Alias to inner type |
| AggregateFunction states | Yes | No (explicit rejection) | No | No | No | Opaque representation or explicit rejection |
| Geometry aliases (Point, Ring, Polygon, MultiPolygon, LineString, MultiLineString) | Yes | Yes (as their native tuple and array shapes) | Yes (as their native shapes) | No | No | Native representations of nested types |
| Variant | Yes | Yes | No (write the concrete type) | Yes (native representation via getObject) | Unscheduled | Basic and compact discriminator modes; discriminator validation |
| Dynamic | Yes | Yes | No (write a concrete type) | Yes (native representation via getObject) | Unscheduled | V1 and V2 structure; shared variant rows fail explicitly on access, raw bytes exposed |
| JSON | Yes | Yes | No (explicit rejection) | Yes (path map via getObject) | Unscheduled | V1 and V2 object serialisation; typed and dynamic paths decoded, shared data as raw values |
| QBit and newly introduced types | No (explicit rejection) | No | No | No | No | Explicit `UnsupportedClickHouseTypeException` planned rather than guessing |

Rules that govern this table:

- A cell only becomes `Yes` together with encode and decode round trip tests and integration
  coverage against real servers.
- CHord never guesses the size of an unsupported value and keeps reading; unsupported types fail
  explicitly at the type parsing layer.
- The type name parser handles nested parentheses, quoted enum labels, escaped strings, named
  and back quoted tuple elements, timezone parameters, precision and scale, whitespace variants,
  geometry aliases and malformed input, with configurable nesting and size limits, and is
  round trip property tested.
- Columns using custom serialisation metadata (revision 54454) decode for the default and
  sparse kinds, sparse columns materialising into full columns; the detached, replicated and
  combination kind stacks are inter server forms that fail explicitly before any value bytes are
  consumed.
