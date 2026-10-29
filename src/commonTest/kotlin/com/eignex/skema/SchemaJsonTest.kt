package com.eignex.skema

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SchemaJsonTest {
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
