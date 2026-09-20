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

// Cycling-dynamics developer fields. Base = 32, deliberately clear of the real-meter slot range
// (slot N = 4 + N*4): 32 reserves fields 4..31 for up to 7 future meter slots, so raising
// MAX_METERS can never collide with dynamics. NOT yet shipped, hence free to choose now; once
// shipped these numbers are PUBLIC API — never renumber.
const val DYN_FIELD_BASE = 32
object DynField {
    const val TE_LEFT = DYN_FIELD_BASE          // 32 %
    const val TE_RIGHT = DYN_FIELD_BASE + 1     // 33 %
    const val PS_LEFT = DYN_FIELD_BASE + 2      // 34 %
    const val PS_RIGHT = DYN_FIELD_BASE + 3     // 35 %
    const val PP_START_L = DYN_FIELD_BASE + 4   // 36 deg
    const val PP_END_L = DYN_FIELD_BASE + 5     // 37 deg
    const val PP_START_R = DYN_FIELD_BASE + 6   // 38 deg
    const val PP_END_R = DYN_FIELD_BASE + 7     // 39 deg
    const val PEAK_START_L = DYN_FIELD_BASE + 8 // 40 deg
    const val PEAK_END_L = DYN_FIELD_BASE + 9   // 41 deg
    const val PEAK_START_R = DYN_FIELD_BASE + 10// 42 deg
    const val PEAK_END_R = DYN_FIELD_BASE + 11  // 43 deg
    const val PCO_LEFT = DYN_FIELD_BASE + 12    // 44 mm
    const val PCO_RIGHT = DYN_FIELD_BASE + 13   // 45 mm
    const val BARYCENTER = DYN_FIELD_BASE + 14  // 46 deg
    const val RIDER_POSITION = DYN_FIELD_BASE + 15 // 47 enum 0..3
    const val TORQUE_LEFT = DYN_FIELD_BASE + 16 // 48 Nm
    const val TORQUE_RIGHT = DYN_FIELD_BASE + 17// 49 Nm
    const val BALANCE_LEFT = DYN_FIELD_BASE + 18  // 50 % (left power balance)
    const val BALANCE_RIGHT = DYN_FIELD_BASE + 19 // 51 % (right power balance)
}

/**
 * STANDARD FIT `record` (global mesg 20) field numbers for cycling dynamics. We ONLY use the ones the
 * Karoo does NOT write natively for the bound sensor — currently just PCO. Balance / torque-effectiveness
 * / pedal-smoothness ARE written natively by the Karoo when the meter is paired natively (verified in a
 * Karoo-recorded FIT), so writing the standard fields too would double-write/conflict (and with a
 * different balance convention). KPower therefore writes balance/TE/PS as DEVELOPER fields instead (see
 * [DynField]) — no collision with the Karoo's native standard fields, present for the KPW-virtual case,
 * and a harmless duplicate-in-a-separate-column for the native case. power_phase (69-72) are FIT ARRAY
 * fields that karoo-ext's scalar FieldValue can't address, so those are developer fields too.
 */
object FitRecordField {
    const val LEFT_PCO = 67                    // mm (sint8) — Karoo does NOT record PCO natively
    const val RIGHT_PCO = 68                   // mm
}
