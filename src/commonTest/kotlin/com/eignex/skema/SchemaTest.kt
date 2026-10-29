package com.eignex.skema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

private data class BoolKey(val name: String)
private data class IntKey(val name: String, val min: Int, val max: Int)

@Suppress("AbstractClassCanBeConcreteClass")
private abstract class FormSchema : Schema<FormField>() {
    protected fun bool(name: String): BoolKey {
        add(name, BoolField)
        return BoolKey(name)
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
        assertEquals(def, SchemaJson.decodeFromString(SchemaDef.serializer(FormField.serializer()), encoded))
    }

    @Test
    fun `definition is a snapshot of the registered entries`() {
        val schema = object : Schema<FormField>() {
            fun flag(name: String) = add(name, BoolField)
        }
        schema.flag("first")
        val before = schema.definition()
        schema.flag("second")

        assertEquals(listOf("first"), before.entries.keys.toList())
        assertEquals(listOf("first", "second"), schema.definition().entries.keys.toList())
    }

    @Test
    fun `delegate rejects a name already registered by assignment`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            object : FormSchema() {
                val assigned = bool("flag")
                val flag by bool()
            }
        }
        assertTrue(ex.message!!.contains("flag"))
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
}
