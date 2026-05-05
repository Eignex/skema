package com.eignex.skema

import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * Builder base for any schema'd Eignex library. Subclasses (e.g. kumulant
 * `StatSchema`, klause `VariableSchema`) declare library-specific delegate
 * methods that call [register] to collect property-named [Named] entries
 * and optionally materialize live state alongside.
 *
 * In skeleton mode (see [skeleton]) the materializer is skipped, so a
 * factory call yields a schema with typed delegate keys but no live state —
 * used by [bindTyped] to recover types from a wire-decoded definition
 * without paying for unused materialization.
 */
abstract class LiveSchema<C : Any> {
    @PublishedApi internal val _entries = mutableListOf<Named<C>>()
    val entries: List<Named<C>> get() = _entries

    val skeletonMode: Boolean = currentSkeletonMode

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
     *     register(config, keyOf = { StatKey<R>(it) }) { name ->
     *         specs.add(StatSpec(StatKey<R>(name), config.materialize(concurrency)))
     *     }
     * ```
     */
    protected inline fun <Key> register(
        config: C,
        crossinline keyOf: (name: String) -> Key,
        crossinline materializer: (name: String) -> Unit,
    ) = PropertyDelegateProvider<LiveSchema<C>, ReadOnlyProperty<LiveSchema<C>, Key>> { _, property ->
        val name = property.name
        add(name, config)
        if (!skeletonMode) materializer(name)
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

    companion object {
        @PublishedApi internal var currentSkeletonMode: Boolean = false

        /**
         * Run [factory] in skeleton mode — the returned schema has empty
         * live state but a fully populated [entries]. Reentrant.
         */
        inline fun <T : LiveSchema<*>> skeleton(factory: () -> T): T {
            val prev = currentSkeletonMode
            currentSkeletonMode = true
            try { return factory() } finally { currentSkeletonMode = prev }
        }

        /** Like [skeleton] but for arbitrary blocks that don't return a `LiveSchema`. */
        inline fun <R> withSkeleton(block: () -> R): R {
            val prev = currentSkeletonMode
            currentSkeletonMode = true
            try { return block() } finally { currentSkeletonMode = prev }
        }
    }
}
