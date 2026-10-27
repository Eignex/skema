@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

/**
 * Strict shape validation ahead of decoding. Validation returns the element to decode, with
 * integral number tokens in integer fields rewritten to plain integer tokens.
 */
internal class JsonShape(private val owner: CompactSerializer<*>?, private val catalog: JsonNodeCatalog) {
    fun validateJson(descriptor: SerialDescriptor, element: JsonElement, path: String): JsonElement {
        val name = descriptor.serialName.removeSuffix("?")
        if (name == JsonElement.serializer().descriptor.serialName) return element
        if (element == JsonNull) {
            if (!descriptor.isNullable) invalid(path, "null is not allowed")
            return element
        }
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
                val child = descriptor.getElementDescriptor(1)
                val values = map.mapValues { (key, value) -> validateJson(child, value, "$path.$key") }
                if (values.all { (key, value) -> value === map.getValue(key) }) map else JsonObject(values)
            }

            SerialKind.ENUM -> {
                val literal = element as? JsonPrimitive
                val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
                if (literal?.isString != true || literal.content !in names) invalid(path, "expected one of $names")
                element
            }

            else -> validatePrimitive(descriptor, element, path)
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
            val required = !descriptor.isElementOptional(index) && !child.isNullable
            if (value == null) {
                if (required) invalid(fieldPath(name), "missing operand '$name'")
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
        val range = when (descriptor.kind) {
            PrimitiveKind.BYTE -> BYTE_RANGE
            PrimitiveKind.SHORT -> SHORT_RANGE
            PrimitiveKind.INT -> INT_RANGE
            PrimitiveKind.LONG -> LONG_RANGE
            else -> null
        }
        if (range != null) {
            val integer = primitive.integerOrNull(range) ?: invalid(path, "expected ${descriptor.kind}")
            return if (primitive.content == integer.toString()) primitive else JsonPrimitive(integer)
        }
        val valid = when (descriptor.kind) {
            PrimitiveKind.BOOLEAN -> !primitive.isString && primitive.booleanOrNull != null
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> !primitive.isString && primitive.doubleOrNull != null
            PrimitiveKind.CHAR -> primitive.isString && primitive.content.length == 1
            PrimitiveKind.STRING -> primitive.isString
            else -> true
        }
        if (!valid) invalid(path, "expected ${descriptor.kind}")
        return primitive
    }
}

private val BYTE_RANGE = Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong()
private val SHORT_RANGE = Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()
private val INT_RANGE = Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
private val LONG_RANGE = Long.MIN_VALUE..Long.MAX_VALUE
private val NUMBER = Regex("""(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?""")
private const val LONG_DIGITS = 19

/**
 * The integer this number token denotes, or null when it is quoted, not integral or outside
 * [range]. JSON Schema's `integer` is defined by value, so decimal and exponent notation such
 * as `2.0` and `1e2` count; the digits are shifted exactly, since binary64 would round integers
 * past 2^53.
 */
private fun JsonPrimitive.integerOrNull(range: LongRange): Long? {
    if (isString) return null
    val (sign, whole, fraction, exponent) = NUMBER.matchEntire(content)?.destructured ?: return null
    val digits = (whole + fraction).trimStart('0')
    if (digits.isEmpty()) return 0L.takeIf { it in range }
    // Unparseable exponents exceed Int; any such shift leaves the range or the integers.
    val shift = (exponent.ifEmpty { "0" }.toIntOrNull() ?: return null).toLong() - fraction.length
    val trailingZeros = digits.length - digits.trimEnd('0').length
    if (shift < -trailingZeros) return null
    val mantissa = if (shift < 0) digits.dropLast((-shift).toInt()) else digits
    val zeros = maxOf(shift, 0L)
    if (mantissa.length + zeros > LONG_DIGITS) return null
    return (sign + mantissa + "0".repeat(zeros.toInt())).toLongOrNull()?.takeIf { it in range }
}

internal fun JsonElement.objectAt(path: String): JsonObject = this as? JsonObject ?: invalid(path, "expected an object")

internal fun invalid(path: String, message: String): Nothing = throw SerializationException("$path: $message")
