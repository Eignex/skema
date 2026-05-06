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

private sealed interface FieldKey { val name: String }
private data class BoolKey(override val name: String) : FieldKey
private data class IntKey(override val name: String, val min: Int, val max: Int) : FieldKey

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
            init { add("flag", BoolField) }
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
            init { add("acceptsTos", BoolField) }
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
}
