@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.Contextual
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

@Serializable
private data class CanonicalAliasCollision(@JsonNames("second") val first: Int, val second: Int)

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

@Serializable
private enum class AliasedMode {
    @JsonNames("legacy")
    Current,
}

private object DelegatedContentSerializer : KSerializer<ContentCount> by ContentCountSerializer

private object TransformedContentSerializer : JsonTransformingSerializer<ContentCount>(ContentCountSerializer)

private object NullableTransformedContentSerializer : JsonTransformingSerializer<ContentCount?>(
    ContentCountSerializer.nullable,
)

class StrictSerializerCompatibilityTest {
    @Test
    fun `field aliases accept integral tokens and report input paths`() {
        val serializer = AliasedCount.serializer()
        assertEquals(
            AliasedCount(2),
            Json.decodeFromJsonElement(serializer, Json.parseToJsonElement("""{"oldCount":2.0}"""), JsonNodeCatalog()),
        )
        assertSerializationFails("$.legacyCount:") {
            Json.decodeFromJsonElement(
                serializer,
                Json.parseToJsonElement("""{"legacyCount":"2"}"""),
                JsonNodeCatalog(),
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
    fun `aliases honor caller configuration and canonical field precedence`() {
        val json = Json { useAlternativeNames = false }
        assertSerializationFails("$.oldCount: unknown field") {
            json.decodeFromJsonElement(
                AliasedCount.serializer(),
                json.parseToJsonElement("""{"oldCount":2}"""),
                JsonNodeCatalog(),
            )
        }
        assertEquals(
            CanonicalAliasCollision(1, 2),
            Json.decodeFromJsonElement(
                CanonicalAliasCollision.serializer(),
                Json.parseToJsonElement("""{"first":1,"second":2}"""),
                JsonNodeCatalog(),
            ),
        )
    }

    @Test
    fun `multiple names for the same field are rejected`() {
        for (wire in listOf("""{"count":1,"oldCount":2}""", """{"legacyCount":1,"oldCount":2}""")) {
            assertSerializationFails("$.oldCount: duplicate field 'count'") {
                Json.decodeFromJsonElement(AliasedCount.serializer(), Json.parseToJsonElement(wire), JsonNodeCatalog())
            }
        }
    }

    @Test
    fun `enum aliases work for values and object map keys`() {
        val serializer = AliasedMode.serializer()
        val catalog = JsonNodeCatalog()
        assertEquals(AliasedMode.Current, Json.decodeFromJsonElement(serializer, JsonPrimitive("legacy"), catalog))
        val map = MapSerializer(serializer, Int.serializer())
        assertEquals(
            mapOf(AliasedMode.Current to 2),
            Json.decodeFromJsonElement(map, Json.parseToJsonElement("""{"legacy":2.0}"""), catalog),
        )
        val json = Json { useAlternativeNames = false }
        assertSerializationFails("$: expected one of") {
            json.decodeFromJsonElement(serializer, JsonPrimitive("legacy"), catalog)
        }
        assertSerializationFails("$.legacy: expected one of") {
            json.decodeFromJsonElement(map, Json.parseToJsonElement("""{"legacy":2}"""), catalog)
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
            assertEquals(ContentCountValue(2), json.decodeFromJsonElement(serializer, document))
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
        for (wire in listOf("""{"count":"2","extra":3}""", """{"count":2.0}""", """{"count":2}""")) {
            val document = json.parseToJsonElement("""{"value":$wire}""")
            val error = assertFailsWith<SerializationException> {
                json.decodeFromJsonElement(ContentHolder.serializer(), document, JsonNodeCatalog())
            }
            assertTrue("$.value: Content-based polymorphic serializers" in error.message.orEmpty(), error.message)
        }
        val ordinary = json.parseToJsonElement("""{"count":2}""")
        assertEquals(ContentCountValue(2), json.decodeFromJsonElement(ContentCountSerializer, ordinary))
        assertSerializationFails("$: Content-based polymorphic serializers") {
            json.decodeFromJsonElement(ContentCountSerializer, ordinary, JsonNodeCatalog())
        }
        assertEquals(null, json.decodeFromJsonElement(ContentCountSerializer.nullable, JsonNull, JsonNodeCatalog()))
    }
}
