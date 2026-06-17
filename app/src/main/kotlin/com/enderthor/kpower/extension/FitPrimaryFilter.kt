package com.enderthor.kpower.extension

/** The estimate's developer fields are omitted only when KPower's virtual device is the
 *  Karoo's bound primary power source (auto-detected) AND the active profile declares the
 *  estimate as its primary source — then it's already in the standard `power`. `estimateIsPrimary`
 *  is global (the virtual device is bound device-wide), so a profile that records the estimate as a
 *  SECONDARY must still write the `est_*` fields. */
fun writeEstimateFields(primarySource: String, estimateIsPrimary: Boolean): Boolean =
    !(estimateIsPrimary && primarySource == "ESTIMATE")

fun writeMeterFields(deviceNumber: Int, primarySource: String, primaryRealDeviceNumber: Int?): Boolean =
    !(primarySource == "REAL" && primaryRealDeviceNumber == deviceNumber)
