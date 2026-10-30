@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import com.eignex.skema.TestSpec
import com.eignex.skema.SchemaJson
import com.eignex.skema.schemaJsonConfig
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
private data class SpecHolder(val spec: TestSpec) : SchemaNode

@Serializable
private data class SpecCollection(val values: List<Map<String, TestSpec?>>) : SchemaNode

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
private data class JsonPlugin(val spec: TestSpec) : PluginSpec

private object SchemaNodeSerializer : CompactSerializer<SchemaNode>("SchemaNode", SchemaNode::class, nodes = {
    listOf(
        jsonNode<SpecHolder>("holder"),
        jsonNode<SpecCollection>("collection"),
        jsonNode<LegacyHolder>("legacy"),
        jsonNode<PluginHolder>("plugin"),
    )
})

class CompactSealedChildTest {
    @Test
    fun `compact TestSpec collections retain nulls and nested schemas`() {
        val value: SchemaNode = SpecCollection(
            listOf(
                linkedMapOf("empty" to null, "schema" to TestSpec.Nullable(TestSpec.Int(min = 0))),
                mapOf("constant" to TestSpec.Const(JsonArray(listOf(JsonNull, JsonPrimitive(1))))),
            ),
        )

        val encoded = SchemaJson.encodeToString(value)

        assertEquals(value, SchemaJson.decodeFromString<SchemaNode>(encoded))
    }

    @Test
    fun `ordinary sealed children use the configured discriminator`() {
        val json = Json { classDiscriminator = "node" }
        val value: SchemaNode = SpecHolder(TestSpec.Int(min = 13))

        val encoded = json.encodeToString(value)

        assertEquals("""{"holder":{"spec":{"node":"Int","min":13}}}""", encoded)
        assertEquals(value, json.decodeFromString<SchemaNode>(encoded))
    }

    @Test
    fun `ordinary sealed children retain array polymorphism`() {
        val json = Json { useArrayPolymorphism = true }
        val value: SchemaNode = SpecHolder(TestSpec.Int(min = 13))

        val encoded = json.encodeToString(value)

        assertEquals("""{"holder":{"spec":["Int",{"min":13}]}}""", encoded)
        assertEquals(value, json.decodeFromString<SchemaNode>(encoded))
    }

    @Test
    fun `mixed sealed and compact trees honor discriminator annotations`() {
        val json = Json { classDiscriminator = "other" }
        val value: SchemaNode = LegacyHolder(LegacyBranch(SpecHolder(TestSpec.Bool)))

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
            assertSerializationFails("$.holder.spec.${'$'}type:") {
                SchemaJson.decodeFromString<SchemaNode>("""{"holder":{"spec":$spec}}""")
            }
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
            assertSerializationFails("$path:") {
                json.decodeFromString<SchemaNode>("""{"holder":{"spec":$spec}}""")
            }
        }
    }

    @Test
    fun `array polymorphic subtype failures retain body indexes`() {
        val json = Json { useArrayPolymorphism = true }

        assertSerializationFails("$.holder.spec[1].min:") {
            json.decodeFromString<SchemaNode>("""{"holder":{"spec":["Int",{"min":"13"}]}}""")
        }
    }

    @Test
    fun `ordinary polymorphic children use the caller serializer module`() {
        val json = Json {
            schemaJsonConfig()
            serializersModule = SerializersModule {
                polymorphic(PluginSpec::class) { subclass(JsonPlugin::class, JsonPlugin.serializer()) }
            }
        }
        val value: SchemaNode = PluginHolder(JsonPlugin(TestSpec.Int(min = 0)))

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

        assertEquals(PluginHolder(JsonPlugin(TestSpec.Bool)), decoded)
    }
}
