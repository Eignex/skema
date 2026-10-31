package com.eignex.skema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder

/** `$type` discriminator; omits defaults and nulls. */
val schemaJsonConfig: JsonBuilder.() -> Unit = {
    classDiscriminator = "\$type"
    encodeDefaults = false
    explicitNulls = false
}

val SchemaJson: Json = Json { schemaJsonConfig() }
