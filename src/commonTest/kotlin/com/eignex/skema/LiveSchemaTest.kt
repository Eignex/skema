package com.eignex.skema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Serializable
private sealed interface ToyVar

@Serializable @SerialName("Bool")
private data object BoolToy : ToyVar

@Serializable @SerialName("Int")
private data class IntToy(val min: Int, val max: Int) : ToyVar

private class ToySchema : LiveSchema<ToyVar>() {
    val materialized = mutableListOf<String>()

    val flag by register(BoolToy, keyOf = { it }) { name -> materialized += name }
    val score by register(IntToy(0, 100), keyOf = { it }) { name -> materialized += name }
}

class LiveSchemaTest {

    @Test
    fun `non-skeleton path populates entries and runs the materializer`() {
        val s = ToySchema()
        assertEquals(listOf("flag", "score"), s.entries.map { it.name })
        assertEquals(listOf("flag", "score"), s.materialized)
        assertEquals("flag", s.flag)
        assertEquals("score", s.score)
        assertFalse(s.skeletonMode)
    }

    @Test
    fun `skeleton path populates entries but skips the materializer`() {
        val s = LiveSchema.skeleton(::ToySchema)
        assertEquals(listOf("flag", "score"), s.entries.map { it.name })
        assertEquals(emptyList(), s.materialized)
        assertTrue(s.skeletonMode)
    }

    @Test
    fun `skeleton mode is reentrant and restores the prior flag`() {
        val outer = LiveSchema.skeleton {
            val inner = ToySchema()
            assertTrue(inner.skeletonMode)
            ToySchema()
        }
        assertTrue(outer.skeletonMode)
        val normal = ToySchema()
        assertFalse(normal.skeletonMode)
        assertEquals(listOf("flag", "score"), normal.materialized)
    }

    @Test
    fun `withSkeleton wraps arbitrary blocks - not just LiveSchema factories`() {
        val captured = LiveSchema.withSkeleton {
            val a = ToySchema()
            val b = ToySchema()
            a.skeletonMode to b.skeletonMode
        }
        assertEquals(true to true, captured)
        assertFalse(ToySchema().skeletonMode)
    }

    @Test
    fun `Named entries serialize through SchemaJson with the standard discriminator`() {
        val s = ToySchema()
        val first = s.entries[0]
        val encoded = SchemaJson.encodeToString(Named.serializer(ToyVar.serializer()), first)
        assertEquals("""{"name":"flag","config":{"${'$'}type":"Bool"}}""", encoded)
    }

    @Test
    fun `default definition() returns SchemaDef with all entries`() {
        val def = ToySchema().definition()
        assertEquals(2, def.size)
        assertEquals(listOf("flag", "score"), def.names)
        assertEquals(BoolToy, def["flag"])
        assertEquals(IntToy(0, 100), def["score"])
    }

    @Test
    fun `definition() round-trips through SchemaJson with ${'$'}type discriminator`() {
        val def = ToySchema().definition()
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
    fun `add() rejects duplicate names with a descriptive message`() {
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
    fun `validate() hook runs at definition() time and propagates exceptions`() {
        class Validating : LiveSchema<ToyVar>() {
            val flag by register(BoolToy, keyOf = { it }) { }
            override fun validate(entries: List<Named<ToyVar>>) {
                require(entries.size >= 2) { "this schema demands at least 2 entries" }
            }
        }
        val s = Validating()
        val ex = assertFailsWith<IllegalArgumentException> { s.definition() }
        assertTrue(ex.message!!.contains("at least 2 entries"))
    }

    @Test
    fun `bindTyped accepts matching definition and returns a Bound`() {
        val live = ToySchema()
        val wire = live.entries

        val bound = bindTyped(
            def = wire,
            factory = ::ToySchema,
            definitionOf = { it.entries },
            materialize = { "live!" },
        )
        assertEquals(wire, bound.schema.entries)
        assertEquals("live!", bound.live)
        assertTrue(bound.schema.skeletonMode)
        assertEquals(emptyList(), bound.schema.materialized)
    }

    @Test
    fun `bindTyped rejects drift loudly`() {
        val wire = ToySchema().entries + Named("extra", BoolToy)
        val ex = assertFailsWith<IllegalArgumentException> {
            bindTyped(
                def = wire,
                factory = ::ToySchema,
                definitionOf = { it.entries },
                materialize = { "live!" },
            )
        }
        assertNotNull(ex.message)
        assertTrue(ex.message!!.contains("ToySchema"))
    }

    @Test
    fun `add() exposed for delegates that don't fit register`() {
        val s = object : LiveSchema<ToyVar>() {
            init {
                add("manual", IntToy(1, 10))
            }
        }
        assertEquals(1, s.entries.size)
        assertEquals("manual", s.entries[0].name)
        assertEquals(IntToy(1, 10), s.entries[0].config)
    }
}
