package com.eignex.skema

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `namespaced prefixes keys and leaves embedded references unchanged`() {
        val namespaced = cells.namespaced("slot")
        assertEquals(listOf("slot.x", "slot.total"), namespaced.names.toList())
        assertEquals(Sum(listOf("x", "external")), namespaced["slot.total"])
        assertEquals(cells.entries.values.toList(), namespaced.entries.values.toList())
    }
}
