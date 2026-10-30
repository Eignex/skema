package com.eignex.skema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Ordinary sealed configuration used as an entry type and as a non-compact child in tests. */
@Serializable
internal sealed interface TestSpec {
    @Serializable
    @SerialName("Bool")
    data object Bool : TestSpec

    @Serializable
    @SerialName("Int")
    data class Int(val min: kotlin.Int? = null, val max: kotlin.Int? = null) : TestSpec

    @Serializable
    @SerialName("String")
    data class Str(val pattern: String? = null) : TestSpec

    @Serializable
    @SerialName("Object")
    data class Object(val properties: Map<String, TestSpec> = emptyMap()) : TestSpec

    @Serializable
    @SerialName("Array")
    data class Array(val items: TestSpec? = null, val uniqueItems: Boolean? = null) : TestSpec

    @Serializable
    @SerialName("Nullable")
    data class Nullable(val inner: TestSpec) : TestSpec

    @Serializable
    @SerialName("Const")
    data class Const(val value: JsonElement) : TestSpec
}
