package com.enderthor.kpower.data

import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt


@Serializable
data class GpsCoordinates(val lat: Double, val lon: Double, val bearing: Double? = 0.0){

    // Haversine formula in kilometers
    fun distanceTo(other: GpsCoordinates): Double {
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = Math.toRadians(other.lat)
        val lon2 = Math.toRadians(other.lon)
        val dlat = lat2 - lat1
        val dlon = lon2 - lon1
        val a = sin(dlat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dlon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        val r = 6371.0
        val distance = r * c

        return distance
    }
}