package com.eignex.skema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private sealed interface ToyConfig

@Serializable
@SerialName("Foo")
private data class FooConfig(val n: Int = 0) : ToyConfig

@Serializable
@SerialName("Bar")
private data class BarConfig(val label: String) : ToyConfig

class NamedTest {

    @Test
    fun `Named round-trips through configured Json with the discriminator`() {
        val entry = Named<ToyConfig>("alpha", BarConfig("hello"))
        val encoded = SchemaJson.encodeToString(entry)
        assertEquals("""{"name":"alpha","config":{"${'$'}type":"Bar","label":"hello"}}""", encoded)
        val decoded = SchemaJson.decodeFromString<Named<ToyConfig>>(encoded)
        assertEquals(entry, decoded)
    }

    @Test
    fun `defaults are suppressed under encodeDefaults false`() {
        val entry = Named<ToyConfig>("a", FooConfig())
        // n=0 is the default and must not appear on the wire.
        assertEquals(
            """{"name":"a","config":{"${'$'}type":"Foo"}}""",
            SchemaJson.encodeToString(entry),
        )
    }
}
