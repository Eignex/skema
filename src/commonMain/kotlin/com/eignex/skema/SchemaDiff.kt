package com.eignex.skema

/** Per-entry difference between two schemas. Equal entries on both sides are omitted. */
data class SchemaDiff<C>(
    val added: Map<String, C>,
    val removed: Map<String, C>,
    val changed: Map<String, Pair<C, C>>,
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

fun <C> SchemaDef<C>.diff(other: SchemaDef<C>): SchemaDiff<C> {
    val added = other.entries.filterKeys { it !in entries }
    val removed = entries.filterKeys { it !in other.entries }
    val changed = entries.mapNotNull { (name, oldConfig) ->
        val newConfig = other.entries[name] ?: return@mapNotNull null
        if (oldConfig == newConfig) null else name to (oldConfig to newConfig)
    }.toMap()
    return SchemaDiff(added, removed, changed)
}
