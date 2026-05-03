package com.eignex.skema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
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
    /** Mirrors how a real library threads a "live" structure alongside entries. */
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
        // Outer skeleton schema (flag set to true)…
        val outer = LiveSchema.skeleton {
            // …builds a nested schema that observes the outer flag.
            val inner = ToySchema()
            assertTrue(inner.skeletonMode, "Inner schema constructed inside skeleton block must also be skeleton")
            ToySchema()
        }
        assertTrue(outer.skeletonMode)
        // After return: flag is back to false.
        val normal = ToySchema()
        assertFalse(normal.skeletonMode)
        assertEquals(listOf("flag", "score"), normal.materialized)
    }

    @Test
    fun `Named entries serialize through SchemaJson with the standard discriminator`() {
        val s = ToySchema()
        val first = s.entries[0]
        val encoded = SchemaJson.encodeToString(Named.serializer(ToyVar.serializer()), first)
        assertEquals("""{"name":"flag","config":{"@type":"Bool"}}""", encoded)
    }

    @Test
    fun `bindTyped accepts matching definition and rejects drift`() {
        val live = ToySchema()
        // Snapshot the entries as the "wire" payload.
        val wire = live.entries

        val (rebuilt, materialized) = bindTyped(
            def = wire,
            factory = ::ToySchema,
            definitionOf = { it.entries },
            materialize = { "live!" },
        )
        assertEquals(wire, rebuilt.entries)
        assertEquals("live!", materialized)
        // Skeleton-mode rebuild left the materialized side empty.
        assertEquals(emptyList(), rebuilt.materialized)
        assertTrue(rebuilt.skeletonMode)

        // Drift fails loudly.
        val bogus = wire + Named("extra", BoolToy)
        val ex = runCatching {
            bindTyped(
                def = bogus,
                factory = ::ToySchema,
                definitionOf = { it.entries },
                materialize = { "live!" },
            )
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is IllegalArgumentException)
        assertTrue(ex.message!!.contains("ToySchema"), "message should name the schema class: ${ex.message}")
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
