package com.enderthor.kpower.extension

/** The primary source's developer fields are omitted (the Karoo already writes it to standard `power`). */
fun writeEstimateFields(primarySource: String, estimateIsPrimary: Boolean): Boolean =
    !(primarySource == "ESTIMATE" || estimateIsPrimary)

fun writeMeterFields(deviceNumber: Int, primarySource: String, primaryRealDeviceNumber: Int?): Boolean =
    !(primarySource == "REAL" && primaryRealDeviceNumber == deviceNumber)
