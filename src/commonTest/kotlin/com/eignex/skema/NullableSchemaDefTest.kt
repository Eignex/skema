package com.eignex.skema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NullableSchemaDefTest {
    @Test
    fun `lookup distinguishes a null entry from a missing name`() {
        val def = SchemaDef<String?>(mapOf("null" to null))

        assertNull(def["null"])
        assertFailsWith<IllegalStateException> { def["missing"] }
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
