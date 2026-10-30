@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

/**
 * Validates one level of JSON as its serializer decodes it. Nested values are validated when
 * their own serializers decode them, so JSON transforming serializers see their raw input.
 */
internal class JsonShape(
    private val owner: CompactSerializer<*>?,
    private val catalog: JsonNodeCatalog,
    private val json: Json,
) {
    private val fieldIndices = mutableMapOf<SerialDescriptor, Map<String, Int>>()

    fun compactSerializer(descriptor: SerialDescriptor): CompactSerializer<*>? =
        if (descriptor.nonNullableSerialName() == owner?.serialName) owner else catalog.find(descriptor)

    fun validateJson(
        descriptor: SerialDescriptor,
        element: JsonElement,
        path: String,
        fieldPath: (String) -> String = { "$path.$it" },
    ) {
        // Contextual serializers resolve their actual descriptor during decoding, including nullability.
        if (descriptor.kind == SerialKind.CONTEXTUAL) return
        when (descriptor.nonNullableSerialName()) {
            JsonElement.serializer().descriptor.serialName -> return

            JsonPrimitive.serializer().descriptor.serialName -> {
                if (element !is JsonPrimitive) invalid(path, "expected a JSON primitive")
                return
            }

            JsonNull.serializer().descriptor.serialName -> {
                if (element != JsonNull) invalid(path, "expected null")
                return
            }
        }
        if (element == JsonNull) {
            if (!descriptor.acceptsJsonNull()) invalid(path, "null is not allowed")
            return
        }
        descriptor.integerBounds()?.let { return it.validate(element, path) }
        if (descriptor.isInline) return validateJson(descriptor.getElementDescriptor(0), element, path, fieldPath)
        // Compact children validate their own bodies when decoded, retaining the caller's path.
        if (compactSerializer(descriptor) != null) return
        when (descriptor.kind) {
            // Polymorphic serializers validate the selected subtype during decoding.
            is PolymorphicKind -> Unit

            StructureKind.CLASS, StructureKind.OBJECT ->
                validateRecord(descriptor, element.objectAt(path), path, fieldPath)

            StructureKind.LIST -> if (element !is JsonArray) invalid(path, "expected an array")

            StructureKind.MAP -> validateJsonMap(json, descriptor, element, path)

            SerialKind.ENUM -> {
                val literal = element as? JsonPrimitive
                if (literal?.isString != true || literal.content !in fields(descriptor, path)) {
                    invalid(path, "expected one of ${descriptor.elementNames.toList()}")
                }
            }

            else -> validatePrimitive(descriptor, element, path)
        }
    }

    fun validateMapKey(descriptor: SerialDescriptor, key: String, path: String) {
        descriptor.integerBounds()?.let { bounds ->
            if (!bounds.contains(key)) invalid(path, "expected an integer map key")
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

    private fun validateRecord(
        descriptor: SerialDescriptor,
        body: JsonObject,
        path: String,
        fieldPath: (String) -> String,
    ) {
        val fields = fields(descriptor, path)
        val supplied = mutableMapOf<Int, String>()
        for (name in body.keys) {
            val index = fields[name] ?: invalid(fieldPath(name), "unknown field '$name'")
            if (supplied.put(index, name) != null) {
                invalid(fieldPath(name), "duplicate field '${descriptor.getElementName(index)}'")
            }
        }
        for (index in 0 until descriptor.elementsCount) {
            val name = supplied[index] ?: descriptor.getElementName(index)
            val value = body[name]
            if (value == null) {
                if (descriptor.isJsonFieldRequired(index, json)) invalid(fieldPath(name), "missing operand '$name'")
            } else if (json.configuration.coerceInputValues) {
                // Json coerces these values without decoding them, so check them before it can.
                validateCoercible(descriptor.getElementDescriptor(index), value, fieldPath(name))
            }
        }
    }

    private fun validateCoercible(descriptor: SerialDescriptor, element: JsonElement, path: String) {
        if (element == JsonNull) {
            if (!descriptor.acceptsJsonNull()) invalid(path, "null is not allowed")
        } else if (descriptor.kind == SerialKind.ENUM) {
            validateJson(descriptor, element, path)
        }
    }

    fun fieldName(descriptor: SerialDescriptor, index: Int, body: JsonObject, path: String): String {
        val fields = fields(descriptor, path)
        return body.keys.firstOrNull { fields[it] == index } ?: descriptor.getElementName(index)
    }

    private fun fields(descriptor: SerialDescriptor, path: String): Map<String, Int> =
        fieldIndices.getOrPut(descriptor) {
            val canonical = (0 until descriptor.elementsCount).associateBy { descriptor.getElementName(it) }
            val fields = canonical.toMutableMap()
            if (json.configuration.useAlternativeNames) {
                for (index in 0 until descriptor.elementsCount) {
                    val aliases = descriptor.getElementAnnotations(index).filterIsInstance<JsonNames>()
                        .flatMap { it.names.toList() }
                    for (alias in aliases.filter { it !in canonical }) {
                        val previous = fields.put(alias, index)
                        if (previous != null && previous != index) invalid(path, "ambiguous field alias '$alias'")
                    }
                }
            }
            fields
        }

    private fun validatePrimitive(descriptor: SerialDescriptor, element: JsonElement, path: String) {
        val primitive = element as? JsonPrimitive ?: invalid(path, "expected ${descriptor.kind}")
        val valid = when (descriptor.kind) {
            PrimitiveKind.BOOLEAN -> !primitive.isString && primitive.booleanOrNull != null

            // Tokens beyond the type's range would decode as infinity, which JSON cannot encode.
            PrimitiveKind.FLOAT -> primitive.isJsonNumber() && primitive.content.toFloat().isFinite()

            PrimitiveKind.DOUBLE -> primitive.isJsonNumber() && primitive.content.toDouble().isFinite()

            PrimitiveKind.CHAR -> primitive.isString && primitive.content.length == 1

            PrimitiveKind.STRING -> primitive.isString

            else -> true
        }
        if (!valid) invalid(path, "expected ${descriptor.kind}")
    }
}

private fun JsonPrimitive.isJsonNumber(): Boolean = !isString && JSON_NUMBER.matches(content)

internal fun JsonElement.objectAt(path: String): JsonObject = this as? JsonObject ?: invalid(path, "expected an object")

internal fun invalid(path: String, message: String): Nothing = throw SerializationException("$path: $message")

internal inline fun <T> withJsonPath(path: String, block: () -> T): T = try {
    block()
} catch (error: SerializationException) {
    throw error
} catch (error: IllegalArgumentException) {
    throw SerializationException("$path: ${error.message}", error)
}
