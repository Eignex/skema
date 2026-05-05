package com.eignex.skema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder

/**
 * Recommended `Json` configuration for Eignex schema payloads: `@type`
 * discriminator, suppressed defaults, no explicit nulls. Apply to your
 * own builder for custom overrides:
 * ```
 * val myJson = Json { schemaJsonConfig(); prettyPrint = true }
 * ```
 * Binary formats (ProtoBuf, Cbor) use tag-number polymorphism and don't
 * need this config.
 */
val schemaJsonConfig: JsonBuilder.() -> Unit = {
    classDiscriminator = "@type"
    encodeDefaults = false
    explicitNulls = false
}

/** Convenience [Json] instance pre-configured with [schemaJsonConfig]. */
val SchemaJson: Json = Json { schemaJsonConfig() }
