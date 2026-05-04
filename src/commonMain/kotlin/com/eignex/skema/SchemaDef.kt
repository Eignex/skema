package com.eignex.skema

import kotlinx.serialization.Serializable

/**
 * Generic, serializable wrapper for a schema's pure-data form: a list of
 * [Named] entries. Library-specific wire types (kumulant `StatSchemaDef`,
 * klause `SchemaDef`) can either reuse this directly via a `typealias` or
 * compose with it when they need extra fields (e.g. klause's separate
 * constraints list).
 *
 * Round-trips through any [kotlinx.serialization.SerialFormat] without
 * skema-side changes — the polymorphism lives on the [C] hierarchy
 * (typically a sealed root in the consuming library).
 */
@Serializable
data class SchemaDef<C>(val entries: List<Named<C>>) {
    /** Number of named entries. */
    val size: Int get() = entries.size

    /** Look up an entry's config by name; throws if not present. */
    operator fun get(name: String): C =
        entries.firstOrNull { it.name == name }?.config
            ?: error("SchemaDef has no entry named '$name'. Available: ${entries.map { it.name }}")

    /** Names of all entries, in declaration order. */
    val names: List<String> get() = entries.map { it.name }
}
