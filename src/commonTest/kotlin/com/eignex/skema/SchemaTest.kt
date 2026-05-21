package com.eignex.skema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
private sealed interface FormField

@Serializable
@SerialName("Bool")
private data object BoolField : FormField

@Serializable
@SerialName("Int")
private data class IntField(val min: Int, val max: Int) : FormField

private sealed interface FieldKey {
    val name: String
}
private data class BoolKey(override val name: String) : FieldKey
private data class IntKey(override val name: String, val min: Int, val max: Int) : FieldKey

@Suppress("AbstractClassCanBeConcreteClass")
private abstract class FormSchema : Schema<FormField>() {
    protected fun bool(name: String): BoolKey {
        add(name, BoolField)
        return BoolKey(name)
    }
    protected fun int(name: String, min: Int, max: Int): IntKey {
        add(name, IntField(min, max))
        return IntKey(name, min, max)
    }
    protected fun bool() = register(BoolField, ::BoolKey)
    protected fun int(min: Int, max: Int) = register(IntField(min, max)) { IntKey(it, min, max) }
}

private object SignupFormSchema : FormSchema() {
    val acceptsTos = bool("acceptsTos")
    val age by int(13, 120)
}

class SchemaTest {

    @Test
    fun `mixed assignment and delegate forms populate entries and return typed keys`() {
        assertEquals(listOf("acceptsTos", "age"), SignupFormSchema.entries.keys.toList())
        assertEquals(BoolKey("acceptsTos"), SignupFormSchema.acceptsTos)
        assertEquals(IntKey("age", 13, 120), SignupFormSchema.age)
    }

    @Test
    fun `default definition returns SchemaDef with all entries`() {
        val def = SignupFormSchema.definition()
        assertEquals(2, def.size)
        assertEquals(setOf("acceptsTos", "age"), def.names)
        assertEquals(BoolField, def["acceptsTos"])
        assertEquals(IntField(13, 120), def["age"])
    }

    @Test
    fun `definition round-trips through SchemaJson with a name-keyed map`() {
        val def = SignupFormSchema.definition()
        val encoded = SchemaJson.encodeToString(SchemaDef.serializer(FormField.serializer()), def)
        assertEquals(
            """{"entries":{""" +
                """"acceptsTos":{"${'$'}type":"Bool"},""" +
                """"age":{"${'$'}type":"Int","min":13,"max":120}""" +
                """}}""",
            encoded,
        )
    }

    @Test
    fun `subclass can override definition for a custom root field name`() {
        @Serializable data class FormSchemaDef(val fields: Map<String, FormField>)

        val custom = object : FormSchema() {
            init {
                add("flag", BoolField)
            }
            fun customDef() = FormSchemaDef(entries)
        }
        val encoded = SchemaJson.encodeToString(FormSchemaDef.serializer(), custom.customDef())
        assertEquals("""{"fields":{"flag":{"${'$'}type":"Bool"}}}""", encoded)
    }

    @Test
    fun `add rejects duplicate names with a descriptive message`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            object : Schema<FormField>() {
                init {
                    add("dup", BoolField)
                    add("dup", IntField(0, 1))
                }
            }
        }
        assertTrue(ex.message!!.contains("dup"))
    }

    @Test
    fun `validate hook runs at definition time and propagates exceptions`() {
        class Validating : Schema<FormField>() {
            init {
                add("acceptsTos", BoolField)
            }
            override fun validate(entries: Map<String, FormField>) {
                require(entries.size >= 2) { "this schema demands at least 2 entries" }
            }
        }
        val ex = assertFailsWith<IllegalArgumentException> { Validating().definition() }
        assertTrue(ex.message!!.contains("at least 2 entries"))
    }

    @Test
    fun `SchemaDef get throws on missing name with available names listed`() {
        val def = SchemaDef<FormField>(mapOf("a" to BoolField, "b" to IntField(0, 1)))
        val ex = assertFailsWith<IllegalStateException> { def["missing"] }
        assertTrue(ex.message!!.contains("missing"))
        assertTrue(ex.message!!.contains("a"))
        assertTrue(ex.message!!.contains("b"))
    }

    @Test
    fun `SchemaDef is a data class with structural equality and copy`() {
        val a = SchemaDef<FormField>(mapOf("x" to BoolField))
        val b = SchemaDef<FormField>(mapOf("x" to BoolField))
        val empty = SchemaDef<FormField>(emptyMap())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != empty)
        assertTrue(!a.equals("not a SchemaDef"))
        assertEquals(empty, a.copy(entries = emptyMap()))
    }

    // diff

    private fun def(vararg pairs: Pair<String, FormField>) = SchemaDef(mapOf(*pairs))

    @Test
    fun `diff between equal schemas is empty`() {
        val a = def("x" to BoolField, "y" to IntField(0, 10))
        val b = def("x" to BoolField, "y" to IntField(0, 10))
        val d = a.diff(b)
        assertTrue(d.isEmpty)
        assertEquals(emptyMap(), d.added)
        assertEquals(emptyMap(), d.removed)
        assertEquals(emptyMap(), d.changed)
    }

    @Test
    fun `diff detects added entries`() {
        val a = def("x" to BoolField)
        val b = def("x" to BoolField, "y" to IntField(0, 10))
        val d = a.diff(b)
        assertEquals(mapOf("y" to IntField(0, 10)), d.added)
        assertTrue(d.removed.isEmpty())
        assertTrue(d.changed.isEmpty())
    }

    @Test
    fun `diff detects removed entries`() {
        val a = def("x" to BoolField, "y" to IntField(0, 10))
        val b = def("x" to BoolField)
        val d = a.diff(b)
        assertEquals(mapOf("y" to IntField(0, 10)), d.removed)
        assertTrue(d.added.isEmpty())
        assertTrue(d.changed.isEmpty())
    }

    @Test
    fun `diff detects changed entries with old and new payloads`() {
        val a = def("x" to BoolField, "y" to IntField(0, 10))
        val b = def("x" to BoolField, "y" to IntField(0, 100))
        val d = a.diff(b)
        assertEquals(mapOf("y" to (IntField(0, 10) to IntField(0, 100))), d.changed)
        assertTrue(d.added.isEmpty())
        assertTrue(d.removed.isEmpty())
    }

    @Test
    fun `diff handles mixed adds removes and changes`() {
        val a = def("kept" to BoolField, "moved" to IntField(0, 10), "gone" to BoolField)
        val b = def("kept" to BoolField, "moved" to IntField(0, 100), "new" to BoolField)
        val d = a.diff(b)
        assertEquals(mapOf("new" to BoolField), d.added)
        assertEquals(mapOf("gone" to BoolField), d.removed)
        assertEquals(mapOf("moved" to (IntField(0, 10) to IntField(0, 100))), d.changed)
        assertTrue(!d.isEmpty)
    }

    // composition

    @Test
    fun `plus combines disjoint schemas preserving order`() {
        val a = def("a" to BoolField, "b" to BoolField)
        val b = def("c" to BoolField, "d" to BoolField)
        assertEquals(listOf("a", "b", "c", "d"), (a + b).entries.keys.toList())
    }

    @Test
    fun `plus throws on overlap and names the overlapping keys`() {
        val a = def("x" to BoolField, "y" to BoolField)
        val b = def("y" to IntField(0, 1), "z" to BoolField)
        val ex = assertFailsWith<IllegalArgumentException> { a + b }
        assertTrue(ex.message!!.contains("y"))
    }

    @Test
    fun `plus with empty is identity`() {
        val a = def("x" to BoolField)
        val empty = def()
        assertEquals(a, a + empty)
        assertEquals(a, empty + a)
    }

    @Test
    fun `namespaced prefixes every key with the default separator`() {
        val a = def("email" to BoolField, "phone" to BoolField)
        assertEquals(listOf("user.email", "user.phone"), a.namespaced("user").entries.keys.toList())
    }

    @Test
    fun `namespaced respects a custom separator`() {
        val a = def("email" to BoolField)
        assertEquals(listOf("user/email"), a.namespaced("user", separator = "/").entries.keys.toList())
    }

    @Test
    fun `namespaced rejects empty prefix`() {
        assertFailsWith<IllegalArgumentException> { def("x" to BoolField).namespaced("") }
    }

    @Test
    fun `plus of two namespaced schemas with different prefixes never overlaps`() {
        val users = def("email" to BoolField).namespaced("user")
        val billing = def("email" to BoolField).namespaced("billing")
        val combined = users + billing
        assertEquals(listOf("user.email", "billing.email"), combined.entries.keys.toList())
    }
}
