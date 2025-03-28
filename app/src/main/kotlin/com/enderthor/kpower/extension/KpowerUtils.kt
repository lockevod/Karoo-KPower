package com.enderthor.kpower.extension

import com.enderthor.kpower.data.Quadruple
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import timber.log.Timber


fun StreamState?.getValueOrDefault(): Double {
    return if (this is StreamState.Streaming) {
        this.dataPoint.singleValue ?: 0.0
    } else {
        0.0
    }
}

fun String.toDoubleLocale(): Double {
    return try {
        replace(',', '.').toDouble()
    } catch (e: NumberFormatException) {
        Timber.e(e, "Error convirtiendo '$this' a Double")
        0.0
    }
}

fun getUserProfileFactors(userProfile: UserProfile): Quadruple<Double, Double, Double, Double> {
    val userMass = userProfile.weight.toDouble()
    val factorMass = if (userProfile.preferredUnit.weight == UserProfile.PreferredUnit.UnitType.IMPERIAL) 0.453592 else 1.0
    val factorDistance = if (userProfile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL) 1.60934 else 1.0
    val factorElevation = if (userProfile.preferredUnit.elevation == UserProfile.PreferredUnit.UnitType.IMPERIAL) 0.3048 else 1.0
    return Quadruple(userMass, factorMass, factorDistance, factorElevation)
}