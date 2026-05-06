package com.eignex.skema

import kotlinx.serialization.Serializable

/**
 * Pure-data, serializable form of a schema: the wire value produced by
 * [Schema.definition]. Round-trips through any kotlinx-serialization
 * format (JSON, ProtoBuf, Cbor) without skema-side changes.
 *
 * The default root field name is `entries`. To ship the wire under a
 * different key (e.g. `stats`, `vars`) or to add adjunct lists, define
 * your own wrapper data class and override [Schema.definition]:
 *
 * ```
 * @Serializable data class StatSchemaDef(val stats: Map<String, StatConfig>)
 * abstract class StatSchema : Schema<StatConfig>() {
 *     override fun definition() = StatSchemaDef(entries)
 * }
 * ```
 */
@Serializable
data class SchemaDef<C>(val entries: Map<String, C>) {
    val size: Int get() = entries.size
    val names: Set<String> get() = entries.keys

    /** Look up an entry's config by name; throws if not present. */
    operator fun get(name: String): C =
        entries[name] ?: error("SchemaDef has no entry named '$name'. Available: ${entries.keys}")
}
