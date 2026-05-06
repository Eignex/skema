package com.eignex.skema

/**
 * Combine two schemas into one. Entry order is preserved (left's entries
 * first, then right's). Throws if any entry name appears in both sides;
 * callers who want last-wins or first-wins semantics map themselves
 * before composing.
 */
operator fun <C> SchemaDef<C>.plus(other: SchemaDef<C>): SchemaDef<C> {
    val overlap = entries.keys intersect other.entries.keys
    require(overlap.isEmpty()) {
        "Cannot combine SchemaDefs: overlapping names $overlap"
    }
    return SchemaDef(LinkedHashMap(entries).also { it += other.entries })
}

/**
 * Prefix every entry name. Default separator is `.` (matches dotted-config
 * conventions: `user.email`, `billing.amount`); pass `/` or `:` for other
 * styles.
 */
fun <C> SchemaDef<C>.namespaced(prefix: String, separator: String = "."): SchemaDef<C> {
    require(prefix.isNotEmpty()) { "Namespace prefix must be non-empty" }
    return SchemaDef(entries.mapKeys { (name, _) -> "$prefix$separator$name" })
}
