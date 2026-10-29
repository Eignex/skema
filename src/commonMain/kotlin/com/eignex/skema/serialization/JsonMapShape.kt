@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Uses the same key carrier as Json when choosing object or alternating-array maps. */
private fun SerialDescriptor.mapKeyCarrier(json: Json): SerialDescriptor = when {
    kind == SerialKind.CONTEXTUAL -> json.serializersModule.getContextualDescriptor(this)?.mapKeyCarrier(json) ?: this
    isInline -> getElementDescriptor(0).mapKeyCarrier(json)
    else -> this
}

internal fun validateJsonMap(
    json: Json,
    descriptor: SerialDescriptor,
    element: JsonElement,
    path: String,
    validateJson: (SerialDescriptor, JsonElement, String) -> JsonElement,
    validateMapKey: (SerialDescriptor, String, String) -> Unit,
): JsonElement {
    val key = descriptor.getElementDescriptor(0)
    val value = descriptor.getElementDescriptor(1)
    val carrier = key.mapKeyCarrier(json)
    if (carrier.kind is PrimitiveKind || carrier.kind == SerialKind.ENUM) {
        val map = element.objectAt(path)
        map.keys.forEach { validateMapKey(key, it, "$path.$it") }
        val values = map.mapValues { (name, entry) -> validateJson(value, entry, "$path.$name") }
        return if (values.all { (name, entry) -> entry === map.getValue(name) }) map else JsonObject(values)
    }
    if (!json.configuration.allowStructuredMapKeys) {
        invalid(path, "structured map keys require allowStructuredMapKeys = true")
    }
    val array = element as? JsonArray ?: invalid(path, "expected an array for structured map keys")
    if (array.size % 2 != 0) invalid("$path[${array.lastIndex}]", "expected a value after the map key")
    val values = array.mapIndexed { index, entry ->
        validateJson(if (index % 2 == 0) key else value, entry, "$path[$index]")
    }
    return if (values.indices.all { values[it] === array[it] }) array else JsonArray(values)
}

/** Child values and paths for either of Json's two map representations. */
internal class JsonMapElements(private val element: JsonElement, private val path: String) {
    private val entries = (element as? JsonObject)?.entries?.toList()

    fun child(index: Int): Pair<JsonElement, String> {
        val objectEntries = entries
        if (objectEntries != null) {
            val entry = objectEntries[index / 2]
            return (if (index % 2 == 0) JsonPrimitive(entry.key) else entry.value) to "$path.${entry.key}"
        }
        val array = element as? JsonArray ?: invalid(path, "expected a map object or array")
        return array[index] to "$path[$index]"
    }

    // Only object keys are coerced to strings by Json; array keys retain their ordinary JSON shape.
    fun isObjectKey(index: Int): Boolean = entries != null && index % 2 == 0
}
