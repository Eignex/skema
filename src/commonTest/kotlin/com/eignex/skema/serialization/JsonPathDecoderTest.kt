@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.Contextual
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals

private object OneOrMany : JsonTransformingSerializer<List<Int>>(ListSerializer(Int.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        element as? JsonArray ?: JsonArray(listOf(element))
}

@Serializable
private enum class Level { LOW, HIGH }

private object CaseInsensitiveLevel : JsonTransformingSerializer<Level>(Level.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        JsonPrimitive(element.jsonPrimitive.content.uppercase())
}

@Serializable
private data class Transformed(
    @Serializable(with = OneOrMany::class) val counts: List<Int>,
    val levels: List<
        @Serializable(with = CaseInsensitiveLevel::class)
        Level,
        > = emptyList(),
    val total: Int = 0,
)

@Serializable(with = TransformedNodeSerializer::class)
private sealed interface TransformedNode

@Serializable
private data class TransformedLeaf(val value: Transformed) : TransformedNode

private object TransformedNodeSerializer : CompactSerializer<TransformedNode>(
    "TransformedNode",
    TransformedNode::class,
    nodes = { listOf(jsonNode<TransformedLeaf>("leaf", JsonNodeShape.Operand, "value")) },
)

class JsonPathDecoderTest {
    @Serializable
    private data class AliasedCount(@JsonNames("oldCount", "legacyCount") val count: Int)

    @Serializable
    private data class AliasedContext(@JsonNames("oldPayload") @Contextual val payload: AliasedCount)

    @Serializable(with = AliasNodeSerializer::class)
    private sealed interface AliasNode

    @Serializable
    private data object AliasLeaf : AliasNode

    private object AliasNodeSerializer : CompactSerializer<AliasNode>(
        "AliasNode",
        AliasNode::class,
        nodes = { listOf(jsonNode<AliasLeaf>("leaf")) },
    )

    @Serializable
    private data class AliasedCompact(@JsonNames("oldNode") val node: AliasNode)

    @Serializable(with = ContentCountSerializer::class)
    private sealed interface ContentCount

    @Serializable
    private data class ContentCountValue(val count: Int) : ContentCount

    private object ContentCountSerializer : JsonContentPolymorphicSerializer<ContentCount>(ContentCount::class) {
        override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ContentCount> =
            ContentCountValue.serializer()
    }

    @Serializable
    private data class ContentHolder(val value: ContentCount)

    private object DelegatedContentSerializer : KSerializer<ContentCount> by ContentCountSerializer

    private object TransformedContentSerializer : JsonTransformingSerializer<ContentCount>(ContentCountSerializer)

    private object NullableTransformedContentSerializer : JsonTransformingSerializer<ContentCount?>(
        ContentCountSerializer.nullable,
    )

    @Serializable(with = DelegatedNodeSerializer::class)
    private sealed interface DelegatedNode

    @Serializable
    private data class DelegatedCount(val value: Int) : DelegatedNode

    private object CanonicalNodeSerializer : CompactSerializer<DelegatedNode>(
        "DelegatedNode",
        DelegatedNode::class,
        nodes = { listOf(jsonNode<DelegatedCount>("count")) },
    )

    private object DelegatedNodeSerializer : KSerializer<DelegatedNode> by CanonicalNodeSerializer

    private companion object {
        val delegatedCatalog = JsonNodeCatalog { listOf(CanonicalNodeSerializer) }
    }

    @Serializable(with = DelegatedEnvelopeSerializer::class)
    private sealed interface DelegatedEnvelope

    @Serializable
    private data class DelegatedHolder(val posterior: DelegatedNode?) : DelegatedEnvelope

    private object DelegatedEnvelopeSerializer : CompactSerializer<DelegatedEnvelope>(
        "DelegatedEnvelope",
        DelegatedEnvelope::class,
        delegatedCatalog,
        nodes = { listOf(jsonNode<DelegatedHolder>("bandit")) },
    )

    @Test
    fun `delegated compact serializers decode roots and nullable nested fields`() {
        val document = Json.parseToJsonElement("""{"count":{"value":2e0}}""")
        assertEquals(
            DelegatedCount(2),
            Json.decodeFromJsonElement(DelegatedNodeSerializer, document, delegatedCatalog),
        )
        assertEquals(
            DelegatedCount(2),
            Json.decodeFromJsonElement(DelegatedNodeSerializer.nullable, document, delegatedCatalog),
        )
        assertEquals(
            null,
            Json.decodeFromJsonElement(DelegatedNodeSerializer.nullable, JsonNull, delegatedCatalog),
        )
        val value = DelegatedHolder(DelegatedCount(2))
        val wire = Json.encodeToString(DelegatedEnvelopeSerializer, value)
        assertEquals(value, Json.decodeFromString(DelegatedEnvelopeSerializer, wire))
    }

    @Test
    fun `delegated compact fields preserve strict validation and nested paths`() {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        for ((payload, path) in listOf(
            """{"value":"2"}""" to "$.bandit.posterior.count.value:",
            """{"value":2,"extra":3}""" to "$.bandit.posterior.count.extra:",
        )) {
            assertSerializationFails(path) {
                json.decodeFromString(
                    DelegatedEnvelopeSerializer,
                    """{"bandit":{"posterior":{"count":$payload}}}""",
                )
            }
        }
    }

    @Test
    fun `transformers around compact serializers validate the transformed payload`() {
        val transformed = object : JsonTransformingSerializer<DelegatedNode>(CanonicalNodeSerializer) {
            override fun transformDeserialize(element: JsonElement): JsonElement = if (element is JsonPrimitive) {
                buildJsonObject {
                    putJsonObject(
                        "count",
                    ) { put("value", element) }
                }
            } else {
                element
            }
        }
        assertEquals(
            DelegatedCount(2),
            Json.decodeFromJsonElement(transformed, JsonPrimitive(2), delegatedCatalog),
        )
        assertSerializationFails("$.count.extra:") {
            Json.decodeFromJsonElement(
                transformed,
                Json.parseToJsonElement("""{"count":{"value":2,"extra":3}}"""),
                delegatedCatalog,
            )
        }
    }

    @Test
    fun `aliased contextual fields retain their values and nested paths`() {
        val json = Json { serializersModule = SerializersModule { contextual(AliasedCount.serializer()) } }
        val serializer = AliasedContext.serializer()
        assertEquals(
            AliasedContext(AliasedCount(2)),
            json.decodeFromJsonElement(
                serializer,
                json.parseToJsonElement("""{"oldPayload":{"oldCount":2e0}}"""),
                JsonNodeCatalog(),
            ),
        )
        assertSerializationFails("$.oldPayload.oldCount:") {
            json.decodeFromJsonElement(
                serializer,
                json.parseToJsonElement("""{"oldPayload":{"oldCount":2.5}}"""),
                JsonNodeCatalog(),
            )
        }
    }

    @Test
    fun `aliased compact fields retain their values and paths`() {
        val catalog = JsonNodeCatalog { listOf(AliasNodeSerializer) }
        val serializer = AliasedCompact.serializer()
        assertEquals(
            AliasedCompact(AliasLeaf),
            Json.decodeFromJsonElement(serializer, Json.parseToJsonElement("""{"oldNode":"leaf"}"""), catalog),
        )
        assertSerializationFails("$.oldNode.unknown:") {
            Json.decodeFromJsonElement(serializer, Json.parseToJsonElement("""{"oldNode":"unknown"}"""), catalog)
        }
    }

    @Test
    fun `polymorphic wrappers cannot decode their delegate outside strict validation`() {
        val json = Json { ignoreUnknownKeys = true }
        val document = json.parseToJsonElement("""{"count":"2","extra":3}""")
        for (serializer in listOf(DelegatedContentSerializer, DelegatedContentSerializer.nullable)) {
            assertSerializationFails("$: Custom polymorphic serializers") {
                json.decodeFromJsonElement(serializer, document, JsonNodeCatalog())
            }
        }
    }

    @Test
    fun `transformers decode their input as Json does`() {
        val json = Json { ignoreUnknownKeys = true }
        val document = json.parseToJsonElement("""{"count":2,"extra":3}""")
        for (serializer in listOf(TransformedContentSerializer, NullableTransformedContentSerializer)) {
            assertEquals(
                json.decodeFromJsonElement(serializer, document),
                json.decodeFromJsonElement(serializer, document, JsonNodeCatalog()),
            )
        }
    }

    @Test
    fun `content polymorphism cannot escape strict validation through permissive Json`() {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        for (wire in listOf("""{"count":"2","extra":3}""", """{"count":2}""")) {
            val document = json.parseToJsonElement("""{"value":$wire}""")
            assertSerializationFails("$.value: Content-based polymorphic serializers") {
                json.decodeFromJsonElement(ContentHolder.serializer(), document, JsonNodeCatalog())
            }
        }
        val ordinary = json.parseToJsonElement("""{"count":2}""")
        assertSerializationFails("$: Content-based polymorphic serializers") {
            json.decodeFromJsonElement(ContentCountSerializer, ordinary, JsonNodeCatalog())
        }
        assertEquals(null, json.decodeFromJsonElement(ContentCountSerializer.nullable, JsonNull, JsonNodeCatalog()))
    }

    @Test
    fun `transformed fields accept what Json accepts while siblings stay strict`() {
        val wire = """{"counts":2e0,"levels":["low","High"],"total":3.0}"""
        val expected = Transformed(listOf(2), listOf(Level.LOW, Level.HIGH), 3)
        assertEquals(expected, Json.decodeFromString<Transformed>(wire.replace("3.0", "3")))
        assertEquals(
            expected,
            Json.decodeFromJsonElement(Transformed.serializer(), Json.parseToJsonElement(wire), JsonNodeCatalog()),
        )
        assertEquals(TransformedLeaf(expected), Json.decodeFromString(TransformedNodeSerializer, """{"leaf":$wire}"""))
        assertSerializationFails("$.leaf.total:") {
            Json.decodeFromString(TransformedNodeSerializer, """{"leaf":{"counts":2,"total":3.5}}""")
        }
        assertSerializationFails("$.leaf.extra:") {
            Json.decodeFromString(TransformedNodeSerializer, """{"leaf":{"counts":2,"extra":1}}""")
        }
    }

    @Test
    fun `coerced values are rejected before Json can replace them`() {
        val json = Json { coerceInputValues = true }
        assertSerializationFails("$.total: null is not allowed") {
            json.decodeFromJsonElement(
                Transformed.serializer(),
                json.parseToJsonElement("""{"counts":[1],"total":null}"""),
                JsonNodeCatalog(),
            )
        }
    }
}
