package com.enderthor.kpower.extension

/** The estimate's developer fields are omitted only when KPower's virtual device is the
 *  Karoo's bound primary power source (auto-detected) — then it's already in standard `power`. */
fun writeEstimateFields(estimateIsPrimary: Boolean): Boolean = !estimateIsPrimary

fun writeMeterFields(deviceNumber: Int, primarySource: String, primaryRealDeviceNumber: Int?): Boolean =
    !(primarySource == "REAL" && primaryRealDeviceNumber == deviceNumber)
