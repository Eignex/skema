package com.eignex.skema

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SchemaJsonTest {
    @Test
    fun `annotation defaults distinguish absent values from explicit JSON null`() {
        for (encodeDefaults in listOf(false, true)) {
            for (explicitNulls in listOf(false, true)) {
                val json = Json {
                    schemaJsonConfig()
                    this.encodeDefaults = encodeDefaults
                    this.explicitNulls = explicitNulls
                }
                for (default in listOf(null, JsonNull)) {
                    val spec: JsonSpec = JsonSpec.Annotated(JsonSpec.Bool, default = default)
                    val wire = json.encodeToString(JsonSpec.serializer(), spec)

                    assertEquals(spec, json.decodeFromString(JsonSpec.serializer(), wire))
                    val rendered = spec.toJsonSchema()
                    assertEquals(default != null, "default" in rendered)
                    if (default != null) assertEquals(JsonNull, rendered["default"])
                }
            }
        }
    }

    @Test
    fun `default configs use stable discriminators and omit optional fields`() {
        val def = SchemaDef(mapOf("count" to JsonSpec.Int(), "label" to JsonSpec.Str()))
        val serializer = SchemaDef.serializer(JsonSpec.serializer())
        val wire = """{"entries":{"count":{"${'$'}type":"Int"},"label":{"${'$'}type":"String"}}}"""

        assertEquals(wire, SchemaJson.encodeToString(serializer, def))
        assertEquals(def, SchemaJson.decodeFromString(serializer, wire))
    }

    @Test
    fun `schemaJsonConfig can be reused and customized`() {
        val json = Json {
            schemaJsonConfig()
            ignoreUnknownKeys = true
        }
        val wire = """{"${'$'}type":"Int","min":1,"futureField":true}"""

        assertEquals(JsonSpec.Int(min = 1), json.decodeFromString(JsonSpec.serializer(), wire))
        assertEquals(
            SchemaJson.encodeToString(JsonSpec.serializer(), JsonSpec.Int()),
            json.encodeToString(JsonSpec.serializer(), JsonSpec.Int()),
        )
    }

    @Test
    fun `SchemaJson rejects unknown fields and unknown variants`() {
        assertFailsWith<SerializationException> {
            SchemaJson.decodeFromString(JsonSpec.serializer(), """{"${'$'}type":"Int","futureField":true}""")
        }
        assertFailsWith<SerializationException> {
            SchemaJson.decodeFromString(JsonSpec.serializer(), """{"${'$'}type":"FutureVariant"}""")
        }
    }
}
