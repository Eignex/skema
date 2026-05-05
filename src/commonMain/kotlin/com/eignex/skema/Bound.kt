package com.eignex.skema

/**
 * Result of [bindTyped]: the typed [LiveSchema] subclass paired with the
 * library-specific live materialized value (e.g. kumulant `StatGroup`).
 * Destructure at the call site: `val (schema, group) = def.bindTo(...)`.
 */
data class Bound<out S : LiveSchema<*>, out L>(val schema: S, val live: L)
