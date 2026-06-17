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
