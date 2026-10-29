package com.eignex.skema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class DiffConfig(val value: Int)

class SchemaDiffTest {
    @Test
    fun `equal configurations are omitted across separate instances`() {
        val before = SchemaDef(mapOf("x" to DiffConfig(1)))
        val after = SchemaDef(mapOf("x" to DiffConfig(1)))

        assertTrue(before.diff(after).isEmpty)
    }

    @Test
    fun `diff handles mixed adds removes and changes`() {
        val before = SchemaDef(mapOf("kept" to 1, "changed" to 2, "removed" to 3))
        val after = SchemaDef(mapOf("kept" to 1, "changed" to 4, "added" to 5))
        val diff = before.diff(after)

        assertEquals(mapOf("added" to 5), diff.added)
        assertEquals(mapOf("removed" to 3), diff.removed)
        assertEquals(mapOf("changed" to (2 to 4)), diff.changed)
    }

    @Test
    fun `diff includes transitions to and from null`() {
        val before = SchemaDef(mapOf("toNull" to "old", "fromNull" to null, "unchanged" to null, "removed" to null))
        val after = SchemaDef(mapOf("toNull" to null, "fromNull" to "new", "unchanged" to null, "added" to null))
        val diff = before.diff(after)

        assertEquals(mapOf("added" to null), diff.added)
        assertEquals(mapOf("removed" to null), diff.removed)
        assertEquals(mapOf("toNull" to ("old" to null), "fromNull" to (null to "new")), diff.changed)
    }
}
