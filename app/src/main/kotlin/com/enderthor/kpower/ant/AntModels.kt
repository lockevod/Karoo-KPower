package com.enderthor.kpower.ant

import kotlinx.serialization.Serializable

/** A meter discovered by the scan. */
data class AntDeviceInfo(val name: String, val deviceNumber: Int)

/** A meter the user chose to record; persisted in DataStore. slot is 0-based and drives FIT field numbers.
 *  [userNamed] = the rider set [label] by hand → brand auto-detect must never overwrite it. */
@Serializable
data class SavedMeter(
    val deviceNumber: Int,
    val label: String,
    val slot: Int,
    val enabled: Boolean = true,
    val userNamed: Boolean = false,
    // Last battery status code (1=New..5=Critical) seen for this meter, persisted so the settings
    // screen can show it at a glance WITHOUT holding the meter's raw ANT channel open (which fights
    // the scan and the radio). Filled during a ride; null until first seen.
    val lastBatteryCode: Int? = null,
)

/** True when [label] is an auto/placeholder name (empty, the bare device number, "Device: N",
 *  "Power #N") rather than a name the rider or brand auto-detect set — so it is safe to overwrite
 *  with a detected model name. Shared by the service brand auto-detect and the settings screen. */
fun isAutoMeterLabel(label: String, deviceNumber: Int): Boolean {
    val l = label.trim()
    return l.isEmpty() || l == deviceNumber.toString() ||
        l.equals("Device: $deviceNumber", ignoreCase = true) ||
        l.startsWith("Device:", ignoreCase = true) ||
        l == "Power #$deviceNumber"
}
