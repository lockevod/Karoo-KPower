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
}

/**
 * STANDARD FIT `record` (global mesg 20) field numbers for cycling dynamics, exactly as a Garmin head
 * unit writes them — so intervals.icu / Garmin Connect recognise KPower's recorded dynamics natively
 * (the Karoo never writes these for a non-bound sensor; KPower writes the FIT directly). Encodings per
 * the FIT global profile; the host's FIT encoder applies each field's scale, so we pass DISPLAY units
 * (%, degrees, mm) — except left_right_balance which is a raw uint8 (bit7 = right indicator).
 */
object FitRecordField {
    const val LEFT_RIGHT_BALANCE = 30          // uint8: bit7=right, bits0-6 = right %
    const val LEFT_TORQUE_EFFECTIVENESS = 43   // % (profile scale 2)
    const val RIGHT_TORQUE_EFFECTIVENESS = 44  // %
    const val LEFT_PEDAL_SMOOTHNESS = 45       // % (profile scale 2)
    const val RIGHT_PEDAL_SMOOTHNESS = 46      // %
    const val LEFT_PCO = 67                    // mm (sint8)
    const val RIGHT_PCO = 68                   // mm
    // NOTE: power_phase (69-72) are FIT ARRAY fields. karoo-ext's FieldValue is a single scalar with no
    // array index, so we can't write them as standard fields — power phase stays in developer fields.
}
