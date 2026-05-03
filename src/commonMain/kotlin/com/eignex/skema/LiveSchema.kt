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
 * only [entries] is populated — used by `bindTyped` to recover typed
 * delegate keys without paying for unused live state.
 */
abstract class LiveSchema<C : Any> {
    @PublishedApi internal val _entries = mutableListOf<Named<C>>()
    val entries: List<Named<C>> get() = _entries

    /** Captured at construction from [LiveSchema.currentSkeletonMode]. */
    val skeletonMode: Boolean = currentSkeletonMode

    /** Append a `Named<C>` to [entries]. Exposed for delegates that don't fit [register]. */
    protected fun add(name: String, config: C) {
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
    }
}
