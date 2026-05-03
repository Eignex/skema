package com.eignex.skema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder

/**
 * Recommended `Json` configuration for Eignex schema payloads. Apply to any
 * [Json] builder for the standard contract: `@type` discriminator,
 * suppressed defaults, no explicit nulls.
 *
 * ```
 * val myJson = Json {
 *     schemaJsonConfig()
 *     prettyPrint = true   // your own overrides
 * }
 * ```
 *
 * skema is format-agnostic — `Named<C>`, `LiveSchema<C>`, and `bindTyped`
 * work with any `SerialFormat` (ProtoBuf, Cbor, …). This config only
 * applies to JSON / kaml-style YAML; binary formats use tag-number
 * polymorphism and don't need a string discriminator.
 */
val schemaJsonConfig: JsonBuilder.() -> Unit = {
    classDiscriminator = "@type"
    encodeDefaults = false
    explicitNulls = false
}

/** Convenience [Json] instance pre-configured with [schemaJsonConfig]. */
val SchemaJson: Json = Json { schemaJsonConfig() }
