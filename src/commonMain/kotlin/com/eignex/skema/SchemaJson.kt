package com.eignex.skema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder

/** Uses the $type discriminator and omits defaults and nulls. */
val schemaJsonConfig: JsonBuilder.() -> Unit = {
    classDiscriminator = "\$type"
    encodeDefaults = false
    explicitNulls = false
}

val SchemaJson: Json = Json { schemaJsonConfig() }
