package com.eignex.skema

import kotlinx.serialization.Serializable

/**
 * Pure-data, serializable form of a schema: the wire value produced by
 * [Schema.definition]. Round-trips through any kotlinx-serialization
 * format (JSON, ProtoBuf, Cbor) without skema-side changes.
 *
 * Library wire types either alias this directly (`typealias StatSchemaDef =
 * SchemaDef<StatConfig>`) or compose with it when they need extra fields
 * (klause adds a separate `constraints` list).
 */
@Serializable
data class SchemaDef<C>(val entries: List<Named<C>>) {
    val size: Int get() = entries.size

    val names: List<String> get() = entries.map { it.name }

    /** Look up an entry's config by name; throws if not present. */
    operator fun get(name: String): C =
        entries.firstOrNull { it.name == name }?.config
            ?: error("SchemaDef has no entry named '$name'. Available: ${entries.map { it.name }}")
}
