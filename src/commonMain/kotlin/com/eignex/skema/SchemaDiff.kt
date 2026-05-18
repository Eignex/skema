package com.eignex.skema

/** Per-entry difference between two schemas. Equal entries on both sides are omitted. */
data class SchemaDiff<C>(
    /** Entries present in the new schema but not the old. */
    val added: Map<String, C>,
    /** Entries present in the old schema but not the new. */
    val removed: Map<String, C>,
    /** Entries present in both schemas whose configs differ, mapped to `(old, new)`. */
    val changed: Map<String, Pair<C, C>>,
) {
    /** True when there are no added, removed, or changed entries. */
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

/** Computes the [SchemaDiff] from this schema to [other]. */
fun <C> SchemaDef<C>.diff(other: SchemaDef<C>): SchemaDiff<C> {
    val added = other.entries.filterKeys { it !in entries }
    val removed = entries.filterKeys { it !in other.entries }
    val changed = entries.mapNotNull { (name, oldConfig) ->
        val newConfig = other.entries[name] ?: return@mapNotNull null
        if (oldConfig == newConfig) null else name to (oldConfig to newConfig)
    }.toMap()
    return SchemaDiff(added, removed, changed)
}
