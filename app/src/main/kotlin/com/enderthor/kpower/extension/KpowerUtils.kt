package com.enderthor.kpower.extension

import io.hammerhead.karooext.models.StreamState
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