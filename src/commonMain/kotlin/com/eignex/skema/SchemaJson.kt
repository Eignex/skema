package com.eignex.skema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder

/**
 * Recommended Json configuration for Eignex schema payloads. Apply to
 * any [Json] builder (`Json { schemaJsonConfig() }`) for the standard
 * `$type` discriminator with suppressed defaults.
 */
val schemaJsonConfig: JsonBuilder.() -> Unit = {
    classDiscriminator = "\$type"
    encodeDefaults = false
    explicitNulls = false
}

/** Convenience [Json] instance pre-configured with [schemaJsonConfig]. */
val SchemaJson: Json = Json { schemaJsonConfig() }
