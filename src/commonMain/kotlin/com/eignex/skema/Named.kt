package com.eignex.skema

import kotlinx.serialization.Serializable

/** Pairs a property-style [name] with a [config] payload. The on-wire envelope for every schema entry. */
@Serializable
data class Named<C>(val name: String, val config: C)
