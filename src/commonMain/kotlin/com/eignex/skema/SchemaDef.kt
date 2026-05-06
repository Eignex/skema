package com.eignex.skema

import kotlinx.serialization.Serializable

/**
 * Pure-data, serializable form of a [Schema]. The default root field name
 * is `entries`; for a different name or adjunct fields, define your own
 * Serializable wrapper and override [Schema.definition].
 */
@Serializable
data class SchemaDef<C>(val entries: Map<String, C>) {
    val size: Int get() = entries.size
    val names: Set<String> get() = entries.keys

    operator fun get(name: String): C =
        entries[name] ?: error("SchemaDef has no entry named '$name'. Available: ${entries.keys}")
}
