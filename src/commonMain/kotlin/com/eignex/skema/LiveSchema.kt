package com.eignex.skema

import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * Builder base for any schema'd Eignex library. Subclasses (e.g. kumulant
 * `StatSchema`, klause `VariableSchema`) declare library-specific delegate
 * methods that call [register] to collect property-named [Named] entries
 * and materialize live state alongside.
 *
 * The pure-data view of the schema is [definition]; the live state lives
 * on the subclass. Two paths:
 *
 *  - **Class is the source of truth.** Producer and consumer share the
 *    schema class as code; build with `MySchema()`, serialize
 *    `definition()` for transport only. No deserialization on the
 *    consumer side.
 *  - **Wire is the source of truth.** No class on the consumer side;
 *    decode `SchemaDef<C>` and use a library-specific materializer to
 *    build the live form. Access by name only.
 *
 * skema supports both shapes from the same definition surface.
 */
abstract class LiveSchema<C : Any> {
    @PublishedApi internal val _entries = mutableListOf<Named<C>>()
    val entries: List<Named<C>> get() = _entries

    /** Append an entry. Throws on duplicate names. */
    protected fun add(name: String, config: C) {
        require(_entries.none { it.name == name }) {
            "Duplicate entry name '$name' in ${this::class.simpleName ?: "schema"}"
        }
        _entries.add(Named(name, config))
    }

    /**
     * Property-delegate helper used by subclass delegates:
     * ```
     * protected fun <R> series(config: SeriesStatConfig<R>) =
     *     register(config, keyOf = { StatKey<R>(it) })
     * ```
     * Returns a typed [Key] for use at call sites. Live state is built
     * separately by walking [entries] (or [definition]) after schema
     * construction.
     */
    protected inline fun <Key> register(
        config: C,
        crossinline keyOf: (name: String) -> Key,
    ) = PropertyDelegateProvider<LiveSchema<C>, ReadOnlyProperty<LiveSchema<C>, Key>> { _, property ->
        val name = property.name
        add(name, config)
        val key = keyOf(name)
        ReadOnlyProperty { _, _ -> key }
    }

    /** Override to enforce cross-entry invariants. Called by [definition]; throw on violation. */
    protected open fun validate(entries: List<Named<C>>) {}

    /**
     * Pure-data, serializable view of this schema. Override only when the
     * library needs a wider wire shape (e.g. klause adds a separate
     * `constraints` list).
     */
    open fun definition(): SchemaDef<C> {
        validate(_entries)
        return SchemaDef(_entries.toList())
    }
}
