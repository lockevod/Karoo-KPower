package com.enderthor.kpower.ant

/**
 * Coarse battery severity derived from the ANT+ common page 0x52 status code (1=New..5=Critical).
 * This is the ONLY reliable battery signal that page gives: it also carries a voltage, but
 * voltage→% needs the cell chemistry (CR2032 vs Li-ion vs AAA) and its discharge curve, which the
 * meter doesn't report — so a trustworthy percentage isn't possible. The 4=LOW / 5=CRITICAL
 * thresholds here are the single source of truth shared by the battery icon and the in-ride alert.
 */
enum class BatteryLevel { UNKNOWN, OK, LOW, CRITICAL }

fun batteryLevelOf(code: Int?): BatteryLevel = when (code) {
    1, 2, 3 -> BatteryLevel.OK
    4 -> BatteryLevel.LOW
    5 -> BatteryLevel.CRITICAL
    else -> BatteryLevel.UNKNOWN
}
