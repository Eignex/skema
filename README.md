# skema

Shared schema-serialization plumbing for Eignex libraries — kumulant, klause,
combo. Pure-data envelope (`Named<C>`), live-builder base with property-delegate
plumbing and skeleton mode (`LiveSchema<C>`), JSON discriminator config
(`schemaJsonConfig` / `SchemaJson`), and a typed-schema bind helper
(`bindTyped`).

Format-agnostic at its core: works with kotlinx-serialization JSON, ProtoBuf,
Cbor, or anything implementing `SerialFormat`. The `@type`-discriminator
contract applies to JSON only; binary formats use tag numbers.

skema is plumbing. It does not define a variable vocabulary or a stat catalog —
those live in consumer libraries.
