package com.enderthor.kpower.ant

import kotlinx.serialization.Serializable

/** A meter discovered by the scan.
 *  [name] = MultiDeviceSearch's generic display name ("Device: N"). [resolvedName] = the brand/model read
 *  via PCC (0x50) once the search is paused (null = not resolved yet). [identifyTried] = an identify
 *  attempt finished (with or without a name) → the UI can stop showing "Identifying…". [battery] = 1..5. */
data class AntDeviceInfo(
    val name: String,
    val deviceNumber: Int,
    val resolvedName: String? = null,   // FULL "Brand Model" (e.g. "Garmin Rally 200") from the 0x50 page
    val identifyTried: Boolean = false,
)

/** A meter the user chose to record; persisted in DataStore. slot is 0-based and drives FIT field numbers.
 *  [userNamed] = the rider set [label] by hand → brand auto-detect must never overwrite it. */
@Serializable
data class SavedMeter(
    val deviceNumber: Int,
    val label: String,
    val slot: Int,
    val enabled: Boolean = true,
    val userNamed: Boolean = false,
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
