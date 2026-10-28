@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

/** Validates JSON before decoding, normalizing integral tokens in integer fields. */
internal class JsonShape(
    private val owner: CompactSerializer<*>?,
    private val catalog: JsonNodeCatalog,
    private val json: Json,
) {
    private val integerKeyPatterns = mutableMapOf<SerialDescriptor, Regex>()

    fun validateJson(descriptor: SerialDescriptor, element: JsonElement, path: String): JsonElement {
        // Contextual serializers resolve their actual descriptor during decoding, including nullability.
        if (descriptor.kind == SerialKind.CONTEXTUAL) return element
        val name = descriptor.serialName.removeSuffix("?")
        when (name) {
            JsonElement.serializer().descriptor.serialName -> return element

            JsonPrimitive.serializer().descriptor.serialName -> {
                if (element !is JsonPrimitive) invalid(path, "expected a JSON primitive")
                return element
            }

            JsonNull.serializer().descriptor.serialName -> {
                if (element != JsonNull) invalid(path, "expected null")
                return element
            }
        }
        if (element == JsonNull) {
            if (!descriptor.acceptsJsonNull()) invalid(path, "null is not allowed")
            return element
        }
        descriptor.integerBounds()?.let { return it.validate(element, path) }
        if (descriptor.isInline) return validateJson(descriptor.getElementDescriptor(0), element, path)
        val serializer = if (name == owner?.serialName) owner else catalog.find(descriptor)
        // Compact children validate their own bodies when decoded, retaining the caller's path.
        if (serializer != null) return element
        return when (descriptor.kind) {
            // Polymorphic serializers validate the selected subtype during decoding.
            is PolymorphicKind -> element

            StructureKind.CLASS, StructureKind.OBJECT -> validateRecord(descriptor, element.objectAt(path), path)

            StructureKind.LIST -> {
                val array = element as? JsonArray ?: invalid(path, "expected an array")
                val child = descriptor.getElementDescriptor(0)
                val items = array.mapIndexed { index, value -> validateJson(child, value, "$path[$index]") }
                if (items.indices.all { items[it] === array[it] }) array else JsonArray(items)
            }

            StructureKind.MAP -> {
                val map = element.objectAt(path)
                val key = descriptor.getElementDescriptor(0)
                map.keys.forEach { validateMapKey(key, it, "$path.$it") }
                val child = descriptor.getElementDescriptor(1)
                val values = map.mapValues { (key, value) -> validateJson(child, value, "$path.$key") }
                if (values.all { (key, value) -> value === map.getValue(key) }) map else JsonObject(values)
            }

            SerialKind.ENUM -> {
                val literal = element as? JsonPrimitive
                val names = descriptor.elementNames.toList()
                if (literal?.isString != true || literal.content !in names) invalid(path, "expected one of $names")
                element
            }

            else -> validatePrimitive(descriptor, element, path)
        }
    }

    fun validateMapKey(descriptor: SerialDescriptor, key: String, path: String) {
        descriptor.integerBounds()?.let { bounds ->
            val pattern = integerKeyPatterns.getOrPut(descriptor) { Regex(bounds.keyPattern()) }
            if (!pattern.matches(key)) invalid(path, "expected an integer map key")
            return
        }
        if (descriptor.isInline) {
            validateMapKey(descriptor.getElementDescriptor(0), key, path)
        } else if (descriptor.kind == PrimitiveKind.BOOLEAN) {
            if (key != "true" && key != "false") invalid(path, "expected a boolean map key")
        } else if (descriptor.kind != PrimitiveKind.FLOAT && descriptor.kind != PrimitiveKind.DOUBLE) {
            validateJson(descriptor, JsonPrimitive(key), path)
        }
    }

    fun validateRecord(
        descriptor: SerialDescriptor,
        body: JsonObject,
        path: String,
        fieldPath: (String) -> String = { "$path.$it" },
    ): JsonObject {
        val fields = (0 until descriptor.elementsCount).associateBy { descriptor.getElementName(it) }
        body.keys.firstOrNull { it !in fields }?.let { invalid("$path.$it", "unknown field '$it'") }
        val rewritten = mutableMapOf<String, JsonElement>()
        for ((name, index) in fields) {
            val value = body[name]
            val child = descriptor.getElementDescriptor(index)
            if (value == null) {
                if (descriptor.isJsonFieldRequired(index, json)) invalid(fieldPath(name), "missing operand '$name'")
            } else {
                val checked = validateJson(child, value, fieldPath(name))
                if (checked !== value) rewritten[name] = checked
            }
        }
        if (rewritten.isEmpty()) return body
        return JsonObject(body.mapValues { (name, value) -> rewritten[name] ?: value })
    }

    private fun validatePrimitive(descriptor: SerialDescriptor, element: JsonElement, path: String): JsonElement {
        val primitive = element as? JsonPrimitive ?: invalid(path, "expected ${descriptor.kind}")
        val valid = when (descriptor.kind) {
            PrimitiveKind.BOOLEAN -> !primitive.isString && primitive.booleanOrNull != null
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> descriptor.numberBounds()!!.contains(primitive)
            PrimitiveKind.CHAR -> primitive.isString && primitive.content.length == 1
            PrimitiveKind.STRING -> primitive.isString
            else -> true
        }
        if (!valid) invalid(path, "expected ${descriptor.kind}")
        return primitive
    }
}

internal fun JsonElement.objectAt(path: String): JsonObject = this as? JsonObject ?: invalid(path, "expected an object")

internal fun invalid(path: String, message: String): Nothing = throw SerializationException("$path: $message")

internal inline fun <T> withJsonPath(path: String, block: () -> T): T = try {
    block()
} catch (error: SerializationException) {
    throw error
} catch (error: IllegalArgumentException) {
    throw SerializationException("$path: ${error.message}", error)
}
