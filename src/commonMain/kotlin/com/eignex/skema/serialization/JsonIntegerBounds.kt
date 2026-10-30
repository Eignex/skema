@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

// Decimal strings retain the full ULong range on every target, without floating-point rounding.
internal class JsonIntegerBounds(private val minimum: String, private val maximum: String) {
    val unsigned: Boolean = !minimum.startsWith('-')

    /** Map keys are plain decimal digits; the decoder also checks the range. */
    val keyPattern: String get() = if (unsigned) "^[0-9]+$" else "^-?[0-9]+$"

    fun validate(element: JsonElement, path: String): JsonPrimitive {
        val primitive = element as? JsonPrimitive ?: invalid(path, "expected an integer")
        return normalize(primitive) ?: invalid(path, "expected an integer in $minimum..$maximum")
    }

    /** Plain integer token for an in-range integral [primitive], or null when it is not one. */
    fun normalize(primitive: JsonPrimitive): JsonPrimitive? {
        val integer = if (primitive.isString) null else normalizeInteger(primitive.content)
        if (integer == null || !contains(integer)) return null
        return if (primitive.content == integer) primitive else JsonUnquotedLiteral(integer)
    }

    private fun contains(integer: String): Boolean = if (integer.startsWith('-')) {
        minimum.startsWith('-') && magnitudeAtMost(integer.drop(1), minimum.drop(1))
    } else {
        magnitudeAtMost(integer, maximum)
    }

    fun containsKey(key: String): Boolean {
        if (!(if (unsigned) UNSIGNED_KEY else SIGNED_KEY).matches(key)) return false
        return normalizeInteger(key)?.let(::contains) == true
    }
}

private val SIGNED_KEY = Regex("-?[0-9]+")
private val UNSIGNED_KEY = Regex("[0-9]+")

internal fun SerialDescriptor.integerBounds(): JsonIntegerBounds? {
    if (isInline) {
        if (getElementDescriptor(0).acceptsJsonNull()) return null
        return when (nonNullableSerialName()) {
            "kotlin.UByte" -> JsonIntegerBounds("0", UByte.MAX_VALUE.toString())
            "kotlin.UShort" -> JsonIntegerBounds("0", UShort.MAX_VALUE.toString())
            "kotlin.UInt" -> JsonIntegerBounds("0", UInt.MAX_VALUE.toString())
            "kotlin.ULong" -> JsonIntegerBounds("0", ULong.MAX_VALUE.toString())
            else -> getElementDescriptor(0).integerBounds()
        }
    }
    return when (kind) {
        PrimitiveKind.BYTE -> JsonIntegerBounds(Byte.MIN_VALUE.toString(), Byte.MAX_VALUE.toString())
        PrimitiveKind.SHORT -> JsonIntegerBounds(Short.MIN_VALUE.toString(), Short.MAX_VALUE.toString())
        PrimitiveKind.INT -> JsonIntegerBounds(Int.MIN_VALUE.toString(), Int.MAX_VALUE.toString())
        PrimitiveKind.LONG -> JsonIntegerBounds(Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString())
        else -> null
    }
}

internal val JSON_NUMBER = Regex("""(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?""")
private const val MAX_INTEGER_DIGITS = 20

// JSON Schema integers are defined by value, so 2.0 and 1e2 are integers too.
private fun normalizeInteger(token: String): String? {
    val (significant, power) = canonicalNumber(token) ?: return null
    if (power < 0 || power > MAX_INTEGER_DIGITS - significant.trimStart('-').length) return null
    return significant + "0".repeat(power.toInt())
}

// Exact decimal value as significant digits and a power of ten, so 2, 2.0 and 2e0 compare equal.
internal fun canonicalNumber(token: String): Pair<String, Long>? {
    val groups = JSON_NUMBER.matchEntire(token)?.groupValues ?: return null
    val (sign, whole, fraction) = groups.subList(1, 4)
    val digits = (whole + fraction).trimStart('0')
    if (digits.isEmpty()) return "0" to 0L
    val significant = digits.trimEnd('0')
    val power = groups[4].ifEmpty { "0" }.toLongOrNull() ?: return null
    return sign + significant to power - fraction.length + (digits.length - significant.length)
}

private fun magnitudeAtMost(value: String, maximum: String): Boolean =
    value.length < maximum.length || (value.length == maximum.length && value <= maximum)
