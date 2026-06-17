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
