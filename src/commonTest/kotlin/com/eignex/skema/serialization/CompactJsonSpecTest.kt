@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import com.eignex.skema.JsonSpec
import com.eignex.skema.SchemaDef
import com.eignex.skema.SchemaJson
import com.eignex.skema.schemaJsonConfig
import com.eignex.skema.toJsonSchema
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable(with = SchemaNodeSerializer::class)
private sealed interface SchemaNode

@Serializable
private data class SpecHolder(val spec: JsonSpec) : SchemaNode

@Serializable
private data class SpecCollection(val values: List<Map<String, JsonSpec?>>) : SchemaNode

@Serializable
private data class LegacyHolder(val value: LegacyNode) : SchemaNode

@Serializable
@JsonClassDiscriminator("kind")
private sealed interface LegacyNode

@Serializable
@SerialName("branch")
private data class LegacyBranch(val child: SchemaNode) : LegacyNode

@Serializable
private data class PluginHolder(@Polymorphic val value: PluginSpec) : SchemaNode

private interface PluginSpec

@Serializable
@SerialName("plugin")
private data class JsonPlugin(val spec: JsonSpec) : PluginSpec

private object SchemaNodeSerializer : CompactSerializer<SchemaNode>("SchemaNode", SchemaNode::class, nodes = {
    listOf(
        jsonNode<SpecHolder>("holder"),
        jsonNode<SpecCollection>("collection"),
        jsonNode<LegacyHolder>("legacy"),
        jsonNode<PluginHolder>("plugin"),
    )
})

class CompactJsonSpecTest {
    @Test
    fun `JsonSpec retains its existing discriminator representation`() {
        val definition = SchemaDef<JsonSpec>(mapOf("age" to JsonSpec.Int(min = 13, max = 120)))
        val serializer = SchemaDef.serializer(JsonSpec.serializer())

        val encoded = SchemaJson.encodeToString(serializer, definition)

        assertEquals("""{"entries":{"age":{"${'$'}type":"Int","min":13,"max":120}}}""", encoded)
        assertEquals(definition, SchemaJson.decodeFromString(serializer, encoded))
    }

    @Test
    fun `JsonSpec variants round trip inside compact nodes`() {
        val specs = listOf(
            JsonSpec.Bool, JsonSpec.Null, JsonSpec.Int(min = 13, max = 120),
            JsonSpec.Long(min = 9007199254740993L), JsonSpec.Num(multipleOf = 0.25),
            JsonSpec.Str(pattern = "^[a-z]+$"), JsonSpec.Enum(listOf("a", "b")),
            JsonSpec.Const(JsonNull), JsonSpec.Const(JsonPrimitive(true)),
            JsonSpec.Const(Json.parseToJsonElement("""{"items":[1,null,"x"]}""")),
            JsonSpec.Array(items = JsonSpec.Nullable(JsonSpec.Str()), uniqueItems = true),
            JsonSpec.Object(mapOf("age" to JsonSpec.Int(min = 0)), listOf("age")),
            JsonSpec.Ref("#/${'$'}defs/User"),
            JsonSpec.OneOf(listOf(JsonSpec.Bool, JsonSpec.Int())),
            JsonSpec.AnyOf(listOf(JsonSpec.Bool, JsonSpec.Null)),
            JsonSpec.AllOf(listOf(JsonSpec.Int(min = 0), JsonSpec.Int(max = 10))),
            JsonSpec.Not(JsonSpec.Null),
            JsonSpec.IfThenElse(JsonSpec.Bool, then = JsonSpec.Int(), otherwise = JsonSpec.Str()),
            JsonSpec.Nullable(JsonSpec.Int()),
            JsonSpec.Annotated(JsonSpec.Null, default = JsonNull),
            JsonSpec.Annotated(
                JsonSpec.Int(),
                title = "Age",
                default = JsonPrimitive(1),
                examples = listOf(JsonNull, JsonPrimitive(2)),
            ),
        )
        for (spec in specs) {
            val value: SchemaNode = SpecHolder(spec)

            val encoded = SchemaJson.encodeToString(value)

            assertEquals(value, SchemaJson.decodeFromString<SchemaNode>(encoded))
        }
    }

    @Test
    fun `compact JsonSpec collections retain nulls and nested schemas`() {
        val value: SchemaNode = SpecCollection(
            listOf(
                linkedMapOf("empty" to null, "schema" to JsonSpec.Nullable(JsonSpec.Int(min = 0))),
                mapOf("constant" to JsonSpec.Const(JsonArray(listOf(JsonNull, JsonPrimitive(1))))),
            ),
        )

        val encoded = SchemaJson.encodeToString(value)

        assertEquals(value, SchemaJson.decodeFromString<SchemaNode>(encoded))
    }

    @Test
    fun `decoded compact schemas retain JSON Schema export semantics`() {
        val spec = JsonSpec.Object(
            properties = mapOf("age" to JsonSpec.Int(min = 13), "active" to JsonSpec.Bool),
            required = listOf("age"),
        )
        val value: SchemaNode = SpecHolder(spec)
        val decoded = SchemaJson.decodeFromString<SchemaNode>(SchemaJson.encodeToString(value))
        val definition = SchemaDef(mapOf("user" to decoded))

        val exported = definition.toJsonSchema { (it as SpecHolder).spec.toJsonSchema() }

        assertEquals(SchemaDef<JsonSpec>(mapOf("user" to spec)).toJsonSchema(), exported)
    }

    @Test
    fun `ordinary sealed children use the configured discriminator`() {
        for (key in listOf("type", "${'$'}type", "node")) {
            val json = Json { classDiscriminator = key }
            val value: SchemaNode = SpecHolder(JsonSpec.Int(min = 13))

            val encoded = json.encodeToString(value)

            val spec = json.parseToJsonElement(encoded).jsonObject["holder"]!!.jsonObject["spec"]!!.jsonObject
            assertEquals(JsonPrimitive("Int"), spec[key])
            assertEquals(value, json.decodeFromString<SchemaNode>(encoded))
        }
    }

    @Test
    fun `ordinary sealed children retain array polymorphism`() {
        val json = Json { useArrayPolymorphism = true }
        val value: SchemaNode = SpecHolder(JsonSpec.Int(min = 13))

        val encoded = json.encodeToString(value)

        assertEquals("""{"holder":{"spec":["Int",{"min":13}]}}""", encoded)
        assertEquals(value, json.decodeFromString<SchemaNode>(encoded))
    }

    @Test
    fun `mixed sealed and compact trees honor discriminator annotations`() {
        val json = Json { classDiscriminator = "other" }
        val value: SchemaNode = LegacyHolder(LegacyBranch(SpecHolder(JsonSpec.Bool)))

        val encoded = json.encodeToString(value)

        assertEquals(
            """{"legacy":{"value":{"kind":"branch","child":{"holder":{"spec":{"other":"Bool"}}}}}}""",
            encoded,
        )
        assertEquals(value, json.decodeFromString<SchemaNode>(encoded))
    }

    @Test
    fun `ordinary sealed validation stays strict under lenient JSON settings`() {
        val json = Json(SchemaJson) {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val cases = listOf(
            """{"${'$'}type":"Int","min":2147483648}""" to "min",
            """{"${'$'}type":"Int","extra":1}""" to "extra",
            """{"${'$'}type":"Array","uniqueItems":"true"}""" to "uniqueItems",
        )
        for ((spec, field) in cases) {
            val payload = """{"holder":{"spec":{"${'$'}type":"Object","properties":{"age":$spec}}}}"""

            val error = assertFailsWith<SerializationException> { json.decodeFromString<SchemaNode>(payload) }

            assertTrue("$.holder.spec.properties.age.$field" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `missing unknown and malformed type names retain their paths`() {
        for (spec in listOf("{}", """{"${'$'}type":"missing"}""", """{"${'$'}type":3}""")) {
            val error = assertFailsWith<SerializationException> {
                SchemaJson.decodeFromString<SchemaNode>("""{"holder":{"spec":$spec}}""")
            }

            assertTrue(error.message.orEmpty().startsWith("$.holder.spec.${'$'}type:"), error.message)
        }
    }

    @Test
    fun `array polymorphism rejects malformed envelopes at their paths`() {
        val json = Json { useArrayPolymorphism = true }
        val cases = listOf(
            "{}" to "$.holder.spec",
            "[]" to "$.holder.spec",
            """["Int"]""" to "$.holder.spec",
            """["Int",{},{}]""" to "$.holder.spec",
            "[3,{}]" to "$.holder.spec[0]",
            "[null,{}]" to "$.holder.spec[0]",
        )
        for ((spec, path) in cases) {
            val error = assertFailsWith<SerializationException> {
                json.decodeFromString<SchemaNode>("""{"holder":{"spec":$spec}}""")
            }

            assertTrue(error.message.orEmpty().startsWith("$path:"), error.message)
        }
    }

    @Test
    fun `array polymorphic subtype failures retain body indexes`() {
        val json = Json { useArrayPolymorphism = true }

        val error = assertFailsWith<SerializationException> {
            json.decodeFromString<SchemaNode>("""{"holder":{"spec":["Int",{"min":"13"}]}}""")
        }

        assertTrue(error.message.orEmpty().startsWith("$.holder.spec[1].min:"), error.message)
    }

    @Test
    fun `ordinary polymorphic children use the caller serializer module`() {
        val json = Json {
            schemaJsonConfig()
            serializersModule = SerializersModule {
                polymorphic(PluginSpec::class) { subclass(JsonPlugin::class, JsonPlugin.serializer()) }
            }
        }
        val value: SchemaNode = PluginHolder(JsonPlugin(JsonSpec.Int(min = 0)))

        val encoded = json.encodeToString(value)

        assertEquals(value, json.decodeFromString<SchemaNode>(encoded))
    }

    @Test
    fun `ordinary polymorphic children support default subtype selection`() {
        val json = Json {
            schemaJsonConfig()
            serializersModule = SerializersModule {
                polymorphic(PluginSpec::class) { defaultDeserializer { JsonPlugin.serializer() } }
            }
        }

        val decoded = json.decodeFromString<SchemaNode>(
            """{"plugin":{"value":{"spec":{"${'$'}type":"Bool"}}}}""",
        )

        assertEquals(PluginHolder(JsonPlugin(JsonSpec.Bool)), decoded)
    }
}
