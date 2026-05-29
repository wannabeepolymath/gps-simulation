package com.gpssimulator.app

import java.time.Instant

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val time: Instant,
)
