package com.enderthor.kpower.extension

import io.hammerhead.karooext.models.StreamState


fun StreamState?.getValueOrDefault(): Double {
    return if (this is StreamState.Streaming) {
        this.dataPoint.singleValue ?: 0.0
    } else {
        0.0
    }
}

/**
 * Parse a user-entered number (comma or dot decimal) to Double. Empty/blank/invalid/non-finite
 * input → 0.0, WITHOUT throwing or logging — these fields are routinely empty (e.g. rider height on
 * a new bike), so this must be quiet noise-free. `toDoubleOrNull` avoids the exception entirely;
 * the `isFinite` guard blocks "NaN"/"Infinity" text from poisoning the power maths.
 */
fun String.toDoubleLocale(): Double =
    trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0