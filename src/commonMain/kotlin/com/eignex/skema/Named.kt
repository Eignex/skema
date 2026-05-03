package com.eignex.skema

import kotlinx.serialization.Serializable

/**
 * Generic envelope: pairs a property-style [name] with a [config] value.
 * Both halves of every Eignex schema live in named entries — variables,
 * stats, constraints, anything the user declares with a property delegate.
 */
@Serializable
data class Named<C>(val name: String, val config: C)
