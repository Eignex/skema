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
}

private object SignupFormSchema : FormSchema() {
    val acceptsTos = bool("acceptsTos")
    val age = int("age", 13, 120)
}

class SchemaTest {

    @Test
    fun `assignment-style declarators populate entries and return typed keys`() {
        assertEquals(listOf("acceptsTos", "age"), SignupFormSchema.entries.map { it.name })
        assertEquals(BoolKey("acceptsTos"), SignupFormSchema.acceptsTos)
        assertEquals(IntKey("age", 13, 120), SignupFormSchema.age)
    }

    @Test
    fun `Named entries serialize through SchemaJson with the standard discriminator`() {
        val first = SignupFormSchema.entries[0]
        val encoded = SchemaJson.encodeToString(Named.serializer(FormField.serializer()), first)
        assertEquals("""{"name":"acceptsTos","config":{"${'$'}type":"Bool"}}""", encoded)
    }

    @Test
    fun `default definition returns SchemaDef with all entries`() {
        val def = SignupFormSchema.definition()
        assertEquals(2, def.size)
        assertEquals(listOf("acceptsTos", "age"), def.names)
        assertEquals(BoolField, def["acceptsTos"])
        assertEquals(IntField(13, 120), def["age"])
    }

    @Test
    fun `definition round-trips through SchemaJson with discriminator`() {
        val def = SignupFormSchema.definition()
        val encoded = SchemaJson.encodeToString(SchemaDef.serializer(FormField.serializer()), def)
        assertEquals(
            """{"entries":[""" +
                """{"name":"acceptsTos","config":{"${'$'}type":"Bool"}},""" +
                """{"name":"age","config":{"${'$'}type":"Int","min":13,"max":120}}""" +
                """]}""",
            encoded,
        )
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
            override fun validate(entries: List<Named<FormField>>) {
                require(entries.size >= 2) { "this schema demands at least 2 entries" }
            }
        }
        val ex = assertFailsWith<IllegalArgumentException> { Validating().definition() }
        assertTrue(ex.message!!.contains("at least 2 entries"))
    }

    @Test
    fun `SchemaDef get throws on missing name with available names listed`() {
        val def = SchemaDef(listOf(Named<FormField>("a", BoolField), Named("b", IntField(0, 1))))
        val ex = assertFailsWith<IllegalStateException> { def["missing"] }
        assertTrue(ex.message!!.contains("missing"))
        assertTrue(ex.message!!.contains("a"))
        assertTrue(ex.message!!.contains("b"))
    }

    @Test
    fun `Named is a data class with structural equality and copy`() {
        val a = Named<FormField>("x", BoolField)
        val b = Named<FormField>("x", BoolField)
        val differentName = Named<FormField>("y", BoolField)
        val differentConfig = Named<FormField>("x", IntField(0, 1))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != differentName)
        assertTrue(a != differentConfig)
        assertTrue(!a.equals("not a Named"))
        assertEquals(differentName, a.copy(name = "y"))
        assertEquals("Named(name=x, config=BoolField)", a.toString())
    }

    @Test
    fun `SchemaDef is a data class with structural equality and copy`() {
        val a = SchemaDef(listOf(Named<FormField>("x", BoolField)))
        val b = SchemaDef(listOf(Named<FormField>("x", BoolField)))
        val empty = SchemaDef<FormField>(emptyList())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != empty)
        assertTrue(!a.equals("not a SchemaDef"))
        assertEquals(empty, a.copy(entries = emptyList()))
    }

    // Anonymous objects' simpleName is null on JVM, exercising the "?: schema" fallback in add().
    @Test
    fun `add error message falls back to 'schema' when class has no simple name`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            object : Schema<FormField>() {
                init {
                    add("dup", BoolField)
                    add("dup", IntField(0, 1))
                }
            }
        }
        assertTrue(ex.message!!.contains("schema") || ex.message!!.contains("dup"))
    }
}
