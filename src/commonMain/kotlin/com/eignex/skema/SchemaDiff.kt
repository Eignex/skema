package com.eignex.skema

/**
 * Per-entry difference between two schemas. Names that exist on both sides
 * with structurally-equal configs are omitted; the rest land in [added],
 * [removed], or [changed].
 *
 * Equality on the config payload is structural (kotlinx-serialization
 * sealed roots are typically data classes / data objects), so this works
 * out of the box for any well-defined config hierarchy.
 */
data class SchemaDiff<C>(
    val added: Map<String, C>,
    val removed: Map<String, C>,
    val changed: Map<String, Pair<C, C>>,
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

/** Compute the per-entry diff between two schemas. */
fun <C> SchemaDef<C>.diff(other: SchemaDef<C>): SchemaDiff<C> {
    val added = other.entries.filterKeys { it !in entries }
    val removed = entries.filterKeys { it !in other.entries }
    val changed = entries.mapNotNull { (name, oldConfig) ->
        val newConfig = other.entries[name] ?: return@mapNotNull null
        if (oldConfig == newConfig) null else name to (oldConfig to newConfig)
    }.toMap()
    return SchemaDiff(added, removed, changed)
}
