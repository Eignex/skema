@file:JvmName("SchemaComposition")

package com.eignex.skema

import kotlin.jvm.JvmName
import kotlin.jvm.JvmOverloads

/** Combine two schemas into one; throws on any overlapping name. */
operator fun <C> SchemaDef<C>.plus(other: SchemaDef<C>): SchemaDef<C> {
    val overlap = entries.keys intersect other.entries.keys
    require(overlap.isEmpty()) {
        "Cannot combine SchemaDefs: overlapping names $overlap"
    }
    return SchemaDef(LinkedHashMap(entries).also { it += other.entries })
}

/**
 * Prefixes entry names with [prefix] and [separator].
 *
 * Only entry keys change; configurations are kept as-is. Skema does not know
 * which configuration values name other entries, so an embedded reference to
 * `x` still says `x` after `x` becomes `slot.x`. Rewrite such references with
 * the traversal of the library that owns the configuration type:
 *
 * ```kotlin
 * val renames = def.names.associateWith { "slot.$it" }
 * val slot = SchemaDef(def.namespaced("slot").entries.mapValues { (_, config) ->
 *     config.renameReferences(renames) // domain-aware, provided by the owning library
 * })
 * ```
 */
@JvmOverloads
fun <C> SchemaDef<C>.namespaced(prefix: String, separator: String = "."): SchemaDef<C> {
    require(prefix.isNotEmpty()) { "Namespace prefix must be non-empty" }
    return SchemaDef(entries.mapKeys { (name, _) -> "$prefix$separator$name" })
}
