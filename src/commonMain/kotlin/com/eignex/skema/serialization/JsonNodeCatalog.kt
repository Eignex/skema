package com.eignex.skema.serialization

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonElement

/**
 * Shared compact serializers, loaded lazily to allow recursive types.
 * The resolver handles duplicate wire names and must return one of its candidates.
 */
class JsonNodeCatalog(
    private val resolve: ((candidates: List<JsonNode>, payload: JsonElement, path: String) -> JsonNode)? = null,
    private val serializers: () -> List<CompactSerializer<*>> = { emptyList() },
) {
    private val entries by lazy {
        val supplied = serializers()
        val byName = supplied.associateBy { it.serialName }
        require(byName.size == supplied.size) { "Compact serializer names must be distinct within a catalog" }
        byName
    }

    internal fun find(descriptor: SerialDescriptor): CompactSerializer<*>? =
        entries[descriptor.serialName.removeSuffix("?")]

    internal fun select(candidates: List<JsonNode>, payload: JsonElement, path: String, missing: String): JsonNode =
        when (candidates.size) {
            0 -> invalid(path, missing)
            1 -> candidates.single()
            else -> resolve(candidates, payload, path)
        }

    private fun resolve(candidates: List<JsonNode>, payload: JsonElement, path: String): JsonNode {
        val node = resolve?.invoke(candidates, payload, path) ?: invalid(path, "ambiguous operator")
        if (node !in candidates) invalid(path, "resolver selected an unsupported node")
        return node
    }
}
