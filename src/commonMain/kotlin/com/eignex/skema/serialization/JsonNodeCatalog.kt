package com.eignex.skema.serialization

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonElement

/**
 * Explicit vocabulary shared by related compact serializers. Suppliers are lazy to support
 * recursive type graphs without global registration. The resolver selects among nodes with
 * the same wire name and must return one of the supplied candidates.
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

    internal fun resolve(candidates: List<JsonNode>, payload: JsonElement, path: String): JsonNode {
        val node = resolve?.invoke(candidates, payload, path) ?: invalid(path, "ambiguous operator")
        if (node !in candidates) invalid(path, "resolver selected an unsupported node")
        return node
    }
}
