package com.eignex.skema

import kotlinx.serialization.Serializable

/**
 * Pure-data, serializable form of a [Schema]. The default root field name
 * is `entries`; for a different name or adjunct fields, define your own
 * Serializable wrapper and override [Schema.definition].
 */
@Serializable
data class SchemaDef<C>(
    /** Entries keyed by name, in declaration order. */
    val entries: Map<String, C>,
) {
    /** Number of entries. */
    val size: Int get() = entries.size

    /** Set of entry names. */
    val names: Set<String> get() = entries.keys

    /** Returns the entry for [name], or throws if no such entry exists. */
    operator fun get(name: String): C =
        entries[name] ?: error("SchemaDef has no entry named '$name'. Available: ${entries.keys}")
}
