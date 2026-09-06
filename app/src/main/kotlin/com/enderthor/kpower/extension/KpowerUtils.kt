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

/**
 * Inverse of [toDoubleLocale] for editable fields: render a Double using the current locale's
 * decimal separator, so a comma-locale user reopening the panel sees "1,5" (what they typed),
 * not "1.5". Just swaps the separator on Double.toString() — no rounding, no forced decimals.
 */
fun Double.toStringLocale(): String =
    toString().replace('.', java.text.DecimalFormatSymbols.getInstance().decimalSeparator)

/**
 * Keep only what makes a signed integer: an optional leading '-' plus digits. Everything else
 * (dots, commas, letters, extra signs) is dropped as the rider types, so the offset fields accept
 * only whole positive/negative numbers regardless of what the soft keyboard offers. "" and "-" are
 * valid intermediate states (rider mid-typing) and parse to 0.0 via [toDoubleLocale].
 */
fun signedIntFilter(s: String): String {
    val sign = if (s.startsWith('-')) "-" else ""
    return sign + s.filter { it.isDigit() }
}