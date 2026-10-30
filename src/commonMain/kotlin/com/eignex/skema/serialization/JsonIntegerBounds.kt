@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// Decimal strings retain the full ULong range on every target.
internal class JsonIntegerBounds(private val minimum: String, private val maximum: String) {
    private val signed = minimum.startsWith('-')

    /** Schema pattern for map keys; the decoder also checks the range. */
    val keyPattern: String get() = if (signed) "^-?[0-9]+$" else "^[0-9]+$"

    val unsigned: Boolean get() = !signed

    fun validate(element: JsonElement, path: String) {
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString || !contains(primitive.content)) {
            invalid(path, "expected an integer in $minimum..$maximum")
        }
    }

    fun contains(token: String): Boolean = if (signed) {
        val value = token.takeIf { SIGNED_INTEGER.matches(it) }?.toLongOrNull()
        value != null && value >= minimum.toLong() && value <= maximum.toLong()
    } else {
        val value = token.takeIf { UNSIGNED_INTEGER.matches(it) }?.toULongOrNull()
        value != null && value <= maximum.toULong()
    }
}

private val SIGNED_INTEGER = Regex("-?[0-9]+")
private val UNSIGNED_INTEGER = Regex("[0-9]+")

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
