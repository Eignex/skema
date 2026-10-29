@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.Contextual
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals

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
        val serializers = listOf(
            DelegatedContentSerializer,
            TransformedContentSerializer,
            NullableTransformedContentSerializer,
            DelegatedContentSerializer.nullable,
        )
        for (serializer in serializers) {
            val document = json.parseToJsonElement("""{"count":"2","extra":3}""")
            assertSerializationFails("$: Custom polymorphic serializers") {
                json.decodeFromJsonElement(serializer, document, JsonNodeCatalog())
            }
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
}
