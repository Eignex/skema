package com.eignex.skema

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class Key(val name: String)

@Suppress("AbstractClassCanBeConcreteClass")
private abstract class TestSchema : Schema<TestSpec>() {
    protected fun bool(name: String): Key {
        add(name, TestSpec.Bool)
        return Key(name)
    }

    protected fun int(min: Int) = register(TestSpec.Int(min = min), ::Key)
}

private object Signup : TestSchema() {
    val acceptsTos = bool("acceptsTos")
    val age by int(13)
}

class SchemaTest {
    private val serializer = SchemaDef.serializer(TestSpec.serializer())

    @Test
    fun `entries keep declaration order and round trip through SchemaJson`() {
        val wire = """{"entries":{"acceptsTos":{"${'$'}type":"Bool"},"age":{"${'$'}type":"Int","min":13}}}"""

        assertEquals(Key("acceptsTos"), Signup.acceptsTos)
        assertEquals(Key("age"), Signup.age)
        assertEquals(wire, SchemaJson.encodeToString(serializer, Signup.definition()))
        assertEquals(Signup.definition(), SchemaJson.decodeFromString(serializer, wire))
    }

    @Test
    fun `SchemaJson is strict and its config can be extended`() {
        val future = """{"${'$'}type":"Int","min":1,"futureField":true}"""
        val lenient = Json {
            schemaJsonConfig()
            ignoreUnknownKeys = true
        }

        assertFailsWith<SerializationException> { SchemaJson.decodeFromString(TestSpec.serializer(), future) }
        assertFailsWith<SerializationException> {
            SchemaJson.decodeFromString(TestSpec.serializer(), """{"${'$'}type":"FutureVariant"}""")
        }
        assertEquals(TestSpec.Int(min = 1), lenient.decodeFromString(TestSpec.serializer(), future))
    }

    @Test
    fun `definition validates and snapshots the entries`() {
        val schema = object : TestSchema() {
            fun flag(name: String) = bool(name)

            override fun validate(entries: Map<String, TestSpec>) {
                require(entries.size < 2) { "too many entries" }
            }
        }
        schema.flag("first")
        val before = schema.definition()
        schema.flag("second")

        assertEquals(setOf("first"), before.names)
        assertFailsWith<IllegalArgumentException> { schema.definition() }
        assertFailsWith<IllegalArgumentException> {
            object : TestSchema() {
                val assigned = bool("flag")
                val flag by int(0)
            }
        }
    }

    @Test
    fun `lookup distinguishes null entries from missing names`() {
        val definition = SchemaDef(mapOf("null" to null, "value" to "present"))

        assertNull(definition["null"])
        assertEquals("present", definition["value"])
        val error = assertFailsWith<IllegalStateException> { definition["missing"] }
        assertTrue("[null, value]" in error.message.orEmpty(), error.message)
    }
}
