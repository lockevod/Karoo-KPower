package com.enderthor.kpower.ant

// Estimate developer fields occupy 0..3 (est_power, est_power_3s, est_np, est_avg).
// Each real meter slot uses 4 sequential numbers: power, cadence, balance, torque.
// These numbers are PUBLIC API once shipped — never renumber.
const val EST_FIELD_COUNT = 4
fun fitFieldBase(slot: Int): Int = EST_FIELD_COUNT + slot * 4
fun powerFieldNum(slot: Int): Int = fitFieldBase(slot)
fun cadenceFieldNum(slot: Int): Int = fitFieldBase(slot) + 1
fun balanceFieldNum(slot: Int): Int = fitFieldBase(slot) + 2
fun torqueFieldNum(slot: Int): Int = fitFieldBase(slot) + 3

// Cycling-dynamics developer fields (single meter). Numbers 8.. are PUBLIC API once shipped.
const val DYN_FIELD_BASE = 8
object DynField {
    const val TE_LEFT = DYN_FIELD_BASE          // 8  %
    const val TE_RIGHT = DYN_FIELD_BASE + 1     // 9  %
    const val PS_LEFT = DYN_FIELD_BASE + 2      // 10 %
    const val PS_RIGHT = DYN_FIELD_BASE + 3     // 11 %
    const val PP_START_L = DYN_FIELD_BASE + 4   // 12 deg
    const val PP_END_L = DYN_FIELD_BASE + 5     // 13 deg
    const val PP_START_R = DYN_FIELD_BASE + 6   // 14 deg
    const val PP_END_R = DYN_FIELD_BASE + 7     // 15 deg
    const val PEAK_START_L = DYN_FIELD_BASE + 8 // 16 deg
    const val PEAK_END_L = DYN_FIELD_BASE + 9   // 17 deg
    const val PEAK_START_R = DYN_FIELD_BASE + 10// 18 deg
    const val PEAK_END_R = DYN_FIELD_BASE + 11  // 19 deg
    const val PCO_LEFT = DYN_FIELD_BASE + 12    // 20 mm
    const val PCO_RIGHT = DYN_FIELD_BASE + 13   // 21 mm
    const val BARYCENTER = DYN_FIELD_BASE + 14  // 22 deg
    const val RIDER_POSITION = DYN_FIELD_BASE + 15 // 23 enum 0..3
    const val TORQUE_LEFT = DYN_FIELD_BASE + 16 // 24 Nm
    const val TORQUE_RIGHT = DYN_FIELD_BASE + 17// 25 Nm
}
