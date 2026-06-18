package com.enderthor.kpower.ant

enum class RiderPosition { SEATED, TRANSITION_TO_SEATED, STANDING, TRANSITION_TO_STANDING }

/** 0x10 power-only: instantaneous power + cadence + right-pedal balance %. Null fields = invalid. */
data class PowerOnlyData(
    val eventCount: Int,
    val powerW: Double?,
    val cadenceRpm: Double?,
    val balanceRightPct: Double?,
)

/** 0x13 Torque Effectiveness & Pedal Smoothness (percent). psCombined = b5 was 0xFE. */
data class TePsData(
    val eventCount: Int,
    val teLeftPct: Double?,
    val teRightPct: Double?,
    val psLeftPct: Double?,
    val psRightPct: Double?,
    val psCombined: Boolean,
)

/** 0xE0 right / 0xE1 left force angle. Angles in DEGREES (converted from brads). Arc derived. */
data class ForceAngleData(
    val isLeft: Boolean,
    val eventCount: Int,
    val startAngleDeg: Double?,
    val endAngleDeg: Double?,
    val startPeakDeg: Double?,
    val endPeakDeg: Double?,
    val torqueNm: Double?,
) {
    /** Power-phase arc length (deg), wrapping through 360. Null if either bound invalid. */
    val arcDeg: Double? get() = arc(startAngleDeg, endAngleDeg)
    val peakArcDeg: Double? get() = arc(startPeakDeg, endPeakDeg)
    private fun arc(s: Double?, e: Double?): Double? =
        if (s == null || e == null) null else ((e - s) % 360.0 + 360.0) % 360.0
}

/** 0xE2 pedal position: rider position + cadence + L/R Platform Center Offset (signed mm). */
data class PedalPositionData(
    val eventCount: Int,
    val riderPosition: RiderPosition,
    val cadenceRpm: Double?,
    val rightPcoMm: Int?,
    val leftPcoMm: Int?,
)

/** 0x14 torque barycenter angle (deg). */
data class TorqueBarycenterData(val angleDeg: Double?)

/**
 * 0x11 Standard Wheel Torque / 0x12 Standard Crank Torque. Torque-based meters (e.g. Garmin
 * Rally/Vector) broadcast power HERE, not in the 0x10 power-only page (whose instantaneous-power
 * field they leave at 0). Power/cadence are derived from the DELTA between two consecutive pages —
 * see [CyclingDynamicsParser.torquePower] — so these are raw accumulators, not ready-to-use values.
 * accumPeriod is in 1/2048 s units; accumTorque is in 1/32 Nm units; both are 16-bit and roll over.
 */
data class TorqueData(
    val isCrank: Boolean,
    val eventCount: Int,   // b1, 8-bit rolling
    val ticks: Int,        // b2 wheel/crank ticks (unused for power; kept for completeness)
    val cadenceRpm: Double?, // b3, 0xFF = invalid
    val accumPeriod: Int,  // b4-5 LE, 1/2048 s, 16-bit rolling
    val accumTorque: Int,  // b6-7 LE, 1/32 Nm, 16-bit rolling
)

/** Power/cadence/torque derived from the delta between two consecutive torque pages. */
data class TorquePower(
    val powerW: Double,
    val cadenceRpm: Double,
    val torqueNm: Double,
)
