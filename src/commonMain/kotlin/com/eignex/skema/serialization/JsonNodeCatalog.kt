package com.eignex.skema.serialization

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonElement

/** Compact vocabularies by name; [resolve] breaks wire-name ties. */
class JsonNodeCatalog(
    private val resolve: ((candidates: List<JsonNode>, payload: JsonElement) -> JsonNode)? = null,
    private val serializers: () -> List<CompactSerializer<*>> = { emptyList() },
) {
    private val entries by lazy { serializers().associateBy { it.serialName } }

    internal fun find(descriptor: SerialDescriptor): CompactSerializer<*>? = entries[descriptor.nonNullableSerialName()]

    internal fun select(candidates: List<JsonNode>, payload: JsonElement, missing: () -> String): JsonNode =
        when (candidates.size) {
            0 -> throw SerializationException(missing())

            1 -> candidates.single()

            else -> resolve?.invoke(candidates, payload)
                ?: throw SerializationException("ambiguous operator '${candidates.first().name}'")
        }
}
