@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.Contextual
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
private data class ContextualPayload(val count: Int)

@Serializable
private data class ContextualRecord(@Contextual val payload: ContextualPayload)

@Serializable
private sealed interface ContextualNode

@Serializable
private data class ContextualCompact(@Contextual val payload: ContextualPayload) : ContextualNode

class ContextualSerializationTest {
    private val json = Json {
        serializersModule = SerializersModule { contextual(ContextualPayload.serializer()) }
    }

    @Test
    fun `contextual records decode using the caller module`() {
        val encoded = json.parseToJsonElement("""{"payload":{"count":2}}""")
        for (format in listOf(json, Json.Default)) {
            assertEquals(
                ContextualRecord(ContextualPayload(2)),
                format.decodeFromJsonElement(ContextualRecord.serializer(), encoded, JsonNodeCatalog()),
            )
            assertSerializationFails("$.payload.count:") {
                format.decodeFromJsonElement(
                    ContextualRecord.serializer(),
                    format.parseToJsonElement("""{"payload":{"count":"2"}}"""),
                    JsonNodeCatalog(),
                )
            }
        }
    }

    @Test
    fun `contextual operands retain strict validation and their compact paths`() {
        val serializer = CompactSerializer("ContextualNode", ContextualNode::class) {
            listOf(jsonNode<ContextualCompact>("context", JsonNodeShape.Operand, "payload"))
        }
        val value = ContextualCompact(ContextualPayload(2))
        assertEquals(value, json.decodeFromString(serializer, """{"context":{"count":2}}"""))
        assertSerializationFails("$.context.count:") {
            json.decodeFromString(serializer, """{"context":{"count":2.5}}""")
        }
    }

    @Test
    fun `contextual JSON primitives preserve explicit null at document roots`() {
        val format = Json {
            serializersModule = SerializersModule { contextual(JsonPrimitive.serializer()) }
        }
        assertEquals(
            JsonNull,
            format.decodeFromJsonElement(ContextualSerializer(JsonPrimitive::class), JsonNull, JsonNodeCatalog()),
        )
    }

    @Test
    fun `unregistered contextual schemas explain the required module`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ContextualRecord.serializer().toJsonSchema(JsonNodeCatalog())
        }
        assertTrue("registered serializer or an explicit JSON Schema" in error.message.orEmpty(), error.message)
    }
}
