package com.eignex.skema

import kotlin.test.Test
import kotlin.test.assertEquals

/** Configuration type whose [Sum] entries refer to other entries by name. */
private sealed interface Cell

private data class Input(val default: Int) : Cell

private data class Sum(val terms: List<String>) : Cell

/** Stand-in for the owning library's domain-aware reference traversal. */
private fun Cell.renameReferences(renames: Map<String, String>): Cell = when (this) {
    is Input -> this
    is Sum -> Sum(terms.map { renames[it] ?: it })
}

class SchemaCompositionTest {

    private val cells = SchemaDef(
        mapOf<String, Cell>(
            "x" to Input(1),
            "total" to Sum(listOf("x", "external")),
        ),
    )

    private fun SchemaDef<Cell>.slot(prefix: String): SchemaDef<Cell> {
        val renames = names.associateWith { "$prefix.$it" }
        return SchemaDef(namespaced(prefix).entries.mapValues { (_, cell) -> cell.renameReferences(renames) })
    }

    @Test
    fun `namespaced prefixes keys and leaves embedded references unchanged`() {
        val namespaced = cells.namespaced("slot")
        assertEquals(listOf("slot.x", "slot.total"), namespaced.names.toList())
        assertEquals(Sum(listOf("x", "external")), namespaced["slot.total"])
        assertEquals(cells.entries.values.toList(), namespaced.entries.values.toList())
    }

    @Test
    fun `consumer remaps references to namespaced entries and keeps external ones`() {
        val slot = cells.slot("slot")
        assertEquals(listOf("slot.x", "slot.total"), slot.names.toList())
        assertEquals(Input(1), slot["slot.x"])
        assertEquals(Sum(listOf("slot.x", "external")), slot["slot.total"])
    }

    @Test
    fun `composed slots each reference their own entries`() {
        val combined = cells.slot("a") + cells.slot("b")
        assertEquals(listOf("a.x", "a.total", "b.x", "b.total"), combined.names.toList())
        assertEquals(Sum(listOf("a.x", "external")), combined["a.total"])
        assertEquals(Sum(listOf("b.x", "external")), combined["b.total"])
    }
}
