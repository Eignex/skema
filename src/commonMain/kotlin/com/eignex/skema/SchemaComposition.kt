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

/** Prefix every entry name (default separator `.`). */
@JvmOverloads
fun <C> SchemaDef<C>.namespaced(prefix: String, separator: String = "."): SchemaDef<C> {
    require(prefix.isNotEmpty()) { "Namespace prefix must be non-empty" }
    return SchemaDef(entries.mapKeys { (name, _) -> "$prefix$separator$name" })
}
