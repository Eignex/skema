@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonShapeTest {
    @Serializable
    private data class AliasedCount(@JsonNames("oldCount", "legacyCount") val count: Int)

    @Serializable
    private data class CanonicalAliasCollision(@JsonNames("second") val first: Int, val second: Int)

    @Serializable
    private enum class AliasedMode {
        @JsonNames("legacy")
        Current,
    }

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
}
