package com.eignex.skema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
private sealed interface ToyVar

@Serializable
@SerialName("Bool")
private data object BoolToy : ToyVar

@Serializable
@SerialName("Int")
private data class IntToy(val min: Int, val max: Int) : ToyVar

private sealed interface ToyKey { val name: String }
private data class BoolKey(override val name: String) : ToyKey
private data class IntKey(override val name: String, val min: Int, val max: Int) : ToyKey

private abstract class ToyBaseSchema : LiveSchema<ToyVar>() {
    protected fun bool(name: String): BoolKey {
        add(name, BoolToy)
        return BoolKey(name)
    }
    protected fun int(name: String, min: Int, max: Int): IntKey {
        add(name, IntToy(min, max))
        return IntKey(name, min, max)
    }
}

private object ToySchema : ToyBaseSchema() {
    val flag = bool("flag")
    val score = int("score", 0, 100)
}

class LiveSchemaTest {

    @Test
    fun `assignment-style declarators populate entries and return typed keys`() {
        assertEquals(listOf("flag", "score"), ToySchema.entries.map { it.name })
        assertEquals(BoolKey("flag"), ToySchema.flag)
        assertEquals(IntKey("score", 0, 100), ToySchema.score)
    }

    @Test
    fun `Named entries serialize through SchemaJson with the standard discriminator`() {
        val first = ToySchema.entries[0]
        val encoded = SchemaJson.encodeToString(Named.serializer(ToyVar.serializer()), first)
        assertEquals("""{"name":"flag","config":{"${'$'}type":"Bool"}}""", encoded)
    }

    @Test
    fun `default definition returns SchemaDef with all entries`() {
        val def = ToySchema.definition()
        assertEquals(2, def.size)
        assertEquals(listOf("flag", "score"), def.names)
        assertEquals(BoolToy, def["flag"])
        assertEquals(IntToy(0, 100), def["score"])
    }

    @Test
    fun `definition round-trips through SchemaJson with discriminator`() {
        val def = ToySchema.definition()
        val encoded = SchemaJson.encodeToString(SchemaDef.serializer(ToyVar.serializer()), def)
        assertEquals(
            """{"entries":[""" +
                """{"name":"flag","config":{"${'$'}type":"Bool"}},""" +
                """{"name":"score","config":{"${'$'}type":"Int","min":0,"max":100}}""" +
                """]}""",
            encoded,
        )
    }

    @Test
    fun `add rejects duplicate names with a descriptive message`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            object : LiveSchema<ToyVar>() {
                init {
                    add("dup", BoolToy)
                    add("dup", IntToy(0, 1))
                }
            }
        }
        assertTrue(ex.message!!.contains("dup"))
    }

    @Test
    fun `validate hook runs at definition time and propagates exceptions`() {
        class Validating : LiveSchema<ToyVar>() {
            init { add("flag", BoolToy) }
            override fun validate(entries: List<Named<ToyVar>>) {
                require(entries.size >= 2) { "this schema demands at least 2 entries" }
            }
        }
        val ex = assertFailsWith<IllegalArgumentException> { Validating().definition() }
        assertTrue(ex.message!!.contains("at least 2 entries"))
    }
}
