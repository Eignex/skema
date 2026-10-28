@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

// Decimal strings retain the full ULong range on every target, without floating-point rounding.
internal class JsonIntegerBounds(val minimum: String, val maximum: String) {
    fun validate(element: JsonElement, path: String): JsonPrimitive {
        val primitive = element as? JsonPrimitive ?: invalid(path, "expected an integer")
        val integer = if (primitive.isString) null else normalizeInteger(primitive.content)
        if (integer == null || !contains(integer)) invalid(path, "expected an integer in $minimum..$maximum")
        return if (primitive.content == integer) primitive else JsonUnquotedLiteral(integer)
    }

    private fun contains(integer: String): Boolean = if (integer.startsWith('-')) {
        minimum.startsWith('-') && magnitudeAtMost(integer.drop(1), minimum.drop(1))
    } else {
        magnitudeAtMost(integer, maximum)
    }

    fun keyPattern(): String {
        val positive = "0*(?:${magnitudePattern(maximum)})"
        val negative = if (minimum.startsWith('-')) "|-0*(?:${magnitudePattern(minimum.drop(1))})" else ""
        // Unlike $, this end assertion also rejects a trailing newline in a property name.
        return "^(?:$positive$negative)(?![\\s\\S])"
    }
}

internal fun SerialDescriptor.integerBounds(): JsonIntegerBounds? {
    if (isInline) {
        if (getElementDescriptor(0).acceptsJsonNull()) return null
        return when (serialName.removeSuffix("?")) {
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
    val groups = JSON_NUMBER.matchEntire(token)?.groupValues ?: return null
    val (sign, whole, fraction) = groups.subList(1, 4)
    val digits = (whole + fraction).trimStart('0')
    if (digits.isEmpty()) return "0"
    val shift = (groups[4].ifEmpty { "0" }.toIntOrNull() ?: return null).toLong() - fraction.length
    val trailingZeros = digits.length - digits.trimEnd('0').length
    if (shift < -trailingZeros) return null
    val mantissa = if (shift < 0) digits.dropLast((-shift).toInt()) else digits
    val zeros = maxOf(shift, 0L)
    if (mantissa.length + zeros > MAX_INTEGER_DIGITS) return null
    return sign + mantissa + "0".repeat(zeros.toInt())
}

private fun magnitudeAtMost(value: String, maximum: String): Boolean =
    value.length < maximum.length || (value.length == maximum.length && value <= maximum)

// A bounded decimal range represented lexically, since JSON property names are strings.
private fun magnitudePattern(maximum: String): String {
    val branches = mutableListOf("0")
    if (maximum.length > 1) branches += "[1-9][0-9]{0,${maximum.length - 2}}"
    for (index in maximum.indices) {
        val low = if (index == 0) '1' else '0'
        val high = maximum[index] - 1
        if (high < low) continue
        val digit = if (low == high) low.toString() else "[$low-$high]"
        val remaining = maximum.length - index - 1
        val tail = if (remaining == 0) "" else "[0-9]{$remaining}"
        branches += maximum.take(index) + digit + tail
    }
    branches += maximum
    return branches.joinToString("|")
}
