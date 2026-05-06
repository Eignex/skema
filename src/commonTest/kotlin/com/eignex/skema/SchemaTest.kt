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
}
