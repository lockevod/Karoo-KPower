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
)
