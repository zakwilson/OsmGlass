package dev.glass.phone.ui.search

import dev.glass.phone.routing.LatLng

data class Place(
    val displayName: String,
    val location: LatLng,
)
