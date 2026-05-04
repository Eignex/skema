package com.eignex.skema

import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * Live-builder base for any schema'd Eignex library: collects pure-data
 * `Named<C>` entries via property delegates while letting the consumer
 * decide what (if anything) to materialize on the side.
 *
 * Subclasses (e.g. kumulant `StatSchema`, klause `VariableSchema`) declare
 * library-specific delegate methods that call [register] with a
 * `materializer` lambda for any live state they need to build alongside
 * the wire-data entry. In skeleton mode the materializer is skipped and
 * only [entries] is populated — used by [bindTyped] to recover typed
 * delegate keys without paying for unused live state.
 */
abstract class LiveSchema<C : Any> {
    @PublishedApi internal val _entries = mutableListOf<Named<C>>()
    val entries: List<Named<C>> get() = _entries

    /** Captured at construction from [LiveSchema.currentSkeletonMode]. */
    val skeletonMode: Boolean = currentSkeletonMode

    /**
     * Append a [Named] entry to [entries]. Throws if [name] is already
     * present — names are entry identity, duplicates are always a bug.
     * Property-delegate-built entries can't collide (Kotlin enforces unique
     * property names per class), but [add] is exposed for delegates that
     * don't fit [register] and so does need this check.
     */
    protected fun add(name: String, config: C) {
        require(_entries.none { it.name == name }) {
            "Duplicate entry name '$name' in ${this::class.simpleName ?: "schema"}"
        }
        _entries.add(Named(name, config))
    }

    /**
     * Property-delegate builder. Captures the property name, appends
     * `(name, config)` to [entries], optionally invokes [materializer]
     * (skipped under [skeletonMode]), and returns a typed key via [keyOf].
     *
     * Library-specific delegates wrap this:
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

    /**
     * Hook called by [definition] before producing the wire form. Override
     * to enforce cross-entry invariants (e.g. "schema must be non-empty",
     * "entry X must precede entry Y", referential integrity between
     * config fields). Throw on violation; the message surfaces to
     * [bindTyped] callers and HTTP / config-load error logs.
     *
     * Default: no-op.
     */
    protected open fun validate(entries: List<Named<C>>) {}

    /**
     * Pure-data, serializable view of this schema. Library subclasses
     * override only when they need a wider wire shape (e.g. klause adds a
     * separate `constraints` list); the default returns
     * `SchemaDef(entries)`.
     *
     * Calls [validate] before producing the snapshot.
     */
    open fun definition(): SchemaDef<C> {
        validate(_entries)
        return SchemaDef(_entries.toList())
    }

    companion object {
        /**
         * When `true`, [LiveSchema] subclasses constructed *during this call*
         * skip their materializer in [register] — they collect [entries]
         * only. Single-threaded (KMP-common `var`); flip via [skeleton], do
         * not assign directly.
         */
        @PublishedApi internal var currentSkeletonMode: Boolean = false

        /**
         * Run [factory] in skeleton mode — the returned schema has empty
         * live state but a fully populated [entries] (and thus working
         * typed-key properties on subclass delegates). Reentrant; nested
         * calls preserve the prior flag value.
         */
        inline fun <T : LiveSchema<*>> skeleton(factory: () -> T): T {
            val prev = currentSkeletonMode
            currentSkeletonMode = true
            try {
                return factory()
            } finally {
                currentSkeletonMode = prev
            }
        }

        /**
         * Run an arbitrary [block] in skeleton mode. Use when you need a
         * scope but aren't returning a `LiveSchema` directly (e.g.
         * comparing two factories).
         */
        inline fun <R> withSkeleton(block: () -> R): R {
            val prev = currentSkeletonMode
            currentSkeletonMode = true
            try {
                return block()
            } finally {
                currentSkeletonMode = prev
            }
        }
    }
}
