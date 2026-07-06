package com.enderthor.kpower.vdevice

/**
 * Manual linear correction of a power reading: P_out = max(0, P·(1 + factorPct/100) + offsetW).
 * NaN passes through unchanged (a dropout stays a dropout). Identity when factorPct = 0 and offsetW = 0.
 * factorPct corrects a slope/pendiente error (%); offsetW corrects a constant zero-offset bias (W).
 */
fun applyPowerOffset(power: Double, factorPct: Double, offsetW: Double): Double =
    if (power.isNaN()) power else (power * (1.0 + factorPct / 100.0) + offsetW).coerceAtLeast(0.0)
