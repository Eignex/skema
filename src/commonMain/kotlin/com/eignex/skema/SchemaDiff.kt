@file:JvmName("SchemaDiffs")

package com.eignex.skema

import kotlin.jvm.JvmName

/** Changes from the old schema to the new; equal entries are omitted. */
data class SchemaDiff<C>(
    /** Entries only in the new schema. */
    val added: Map<String, C>,
    /** Entries only in the old schema. */
    val removed: Map<String, C>,
    /** Changed configs as (old, new) pairs. */
    val changed: Map<String, Pair<C, C>>,
) {
    /** Whether the schemas are equal. */
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

/** Compares this schema to the newer [other] schema. */
fun <C> SchemaDef<C>.diff(other: SchemaDef<C>): SchemaDiff<C> {
    val added = other.entries.filterKeys { it !in entries }
    val removed = entries.filterKeys { it !in other.entries }
    val changed = entries.mapNotNull { (name, oldConfig) ->
        if (name !in other.entries) return@mapNotNull null
        val newConfig = other.entries.getValue(name)
        if (oldConfig == newConfig) null else name to (oldConfig to newConfig)
    }.toMap()
    return SchemaDiff(added, removed, changed)
}
