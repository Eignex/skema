package com.eignex.skema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private sealed interface Cell

private data class Input(val default: Int) : Cell

private data class Sum(val terms: List<String>) : Cell

class SchemaCompositionTest {

    private val cells = SchemaDef(
        mapOf<String, Cell>(
            "x" to Input(1),
            "total" to Sum(listOf("x", "external")),
        ),
    )

    @Test
    fun `plus combines disjoint schemas preserving entries and order`() {
        val other = SchemaDef<Cell>(mapOf("last" to Input(2)))

        assertEquals(cells.entries.toList() + other.entries.toList(), (cells + other).entries.toList())
    }

    @Test
    fun `plus rejects overlapping names`() {
        val error = assertFailsWith<IllegalArgumentException> { cells + SchemaDef<Cell>(mapOf("x" to Input(2))) }

        assertTrue("x" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `namespaced prefixes keys and leaves embedded references unchanged`() {
        val namespaced = cells.namespaced("slot")
        assertEquals(listOf("slot.x", "slot.total"), namespaced.names.toList())
        assertEquals(Sum(listOf("x", "external")), namespaced["slot.total"])
        assertEquals(cells.entries.values.toList(), namespaced.entries.values.toList())
    }

    @Test
    fun `namespaced respects a custom separator`() {
        assertEquals(listOf("slot/x", "slot/total"), cells.namespaced("slot", separator = "/").names.toList())
    }

    @Test
    fun `namespaced rejects empty prefix`() {
        assertFailsWith<IllegalArgumentException> { cells.namespaced("") }
    }
}
