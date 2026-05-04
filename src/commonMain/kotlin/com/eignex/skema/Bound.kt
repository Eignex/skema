package com.eignex.skema

/**
 * Result of a typed-schema round-trip via [bindTyped]: pairs the
 * [LiveSchema] subclass (used for typed property-key access) with the
 * library-specific live materialized value (e.g. kumulant `StatGroup`,
 * klause solver `Problem`).
 *
 * Destructure at the call site:
 * ```
 * val (schema, group) = def.bindTyped(::HttpMetrics, …)
 * ```
 */
data class Bound<out S : LiveSchema<*>, out L>(
    val schema: S,
    val live: L,
)
