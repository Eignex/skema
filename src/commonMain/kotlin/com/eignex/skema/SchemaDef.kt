package com.eignex.skema

import kotlinx.serialization.Serializable

/** Serializable entries from a [Schema]. */
@Serializable
data class SchemaDef<C>(val entries: Map<String, C>) {
    val size: Int get() = entries.size

    val names: Set<String> get() = entries.keys

    /** Throws if [name] is absent. */
    operator fun get(name: String): C = if (name in entries) {
        entries.getValue(name)
    } else {
        error("SchemaDef has no entry named '$name'. Available: ${entries.keys}")
    }
}
