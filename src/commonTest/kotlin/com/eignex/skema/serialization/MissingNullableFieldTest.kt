@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable(with = NullableFieldSerializer::class)
private sealed interface NullableFieldValue

@Serializable
private data class NullableFieldRecord(val value: String?, val optional: String? = null) : NullableFieldValue

@Serializable
private data class NullableFieldWrapper(val child: NullableFieldValue) : NullableFieldValue

private object NullableFieldSerializer : CompactSerializer<NullableFieldValue>(
    "NullableFieldValue",
    NullableFieldValue::class,
    nodes = {
        listOf(
            jsonNode<NullableFieldRecord>("record"),
            jsonNode<NullableFieldWrapper>("wrapper", JsonNodeShape.Operand, "child"),
        )
    },
)

class MissingNullableFieldTest {
    @Test
    fun `missing required nullable fields report their compact path`() {
        val json = Json { explicitNulls = true }
        assertSerializationFails("$.wrapper.record.value: missing operand 'value'") {
            json.decodeFromString(NullableFieldSerializer, """{"wrapper":"record"}""")
        }
    }

    @Test
    fun `implicit nullable fields still decode as null`() {
        val json = Json { explicitNulls = false }
        assertEquals(
            NullableFieldWrapper(NullableFieldRecord(null)),
            json.decodeFromString(NullableFieldSerializer, """{"wrapper":"record"}"""),
        )
    }

    @Test
    fun `explicit null satisfies required fields and nullable defaults may be omitted`() {
        val json = Json { explicitNulls = true }
        assertEquals(
            NullableFieldWrapper(NullableFieldRecord(null)),
            json.decodeFromString(NullableFieldSerializer, """{"wrapper":{"record":{"value":null}}}"""),
        )
    }

    @Test
    fun `document decoding checks required nullable fields with its null settings`() {
        val json = Json { explicitNulls = true }
        assertSerializationFails("$.value: missing operand 'value'") {
            json.decodeFromJsonElement(NullableFieldRecord.serializer(), buildJsonObject {}, JsonNodeCatalog())
        }
        assertEquals(
            NullableFieldRecord(null),
            json.decodeFromJsonElement(
                NullableFieldRecord.serializer(),
                buildJsonObject { put("value", JsonNull) },
                JsonNodeCatalog(),
            ),
        )
    }
}
