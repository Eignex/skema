package com.eignex.skema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaDefTest {
    @Test
    fun `lookup distinguishes null entries from missing names and lists available names`() {
        val def = SchemaDef(mapOf("null" to null, "value" to "present"))

        assertNull(def["null"])
        assertEquals("present", def["value"])
        val error = assertFailsWith<IllegalStateException> { def["missing"] }
        for (name in listOf("missing", "null", "value")) {
            assertTrue(name in error.message.orEmpty(), error.message)
        }
    }
}
