package com.enderthor.kpower.ant

/**
 * ANT+ host->device command pages we TRANSMIT over a BIDIRECTIONAL_SLAVE channel — exactly how the
 * Karoo's own sensorservice (io.hammerhead.rxantplus) does it (verified by decompiling hxsensorservice):
 * it does NOT wait for a meter to volunteer its identity/battery on the slow ~30s background rotation,
 * it ASKS via a Request Data Page (common page 70) and the device answers within 1-2 channel periods.
 *
 * A SLAVE_RECEIVE_ONLY channel can't transmit, so it can only ever wait passively — that is why pure
 * receive-only identification is slow and unreliable. These builders are sent with
 * [com.dsi.ant.channel.AntChannel.startSendAcknowledgedData], which requires EXACTLY 8 bytes.
 */
object AntPlusRequests {

    /** ANT+ common pages (decimal page numbers). */
    const val PAGE_MANUFACTURER = 0x50   // 80 — Manufacturer's Identification (brand + model)
    const val PAGE_PRODUCT = 0x51        // 81 — Product Information (sw version + serial)
    const val PAGE_BATTERY = 0x52        // 82 — Battery Status

    /** Identity/status pages a connected meter is asked for on connect (until each is seen). */
    val IDENTITY_PAGES = listOf(PAGE_MANUFACTURER, PAGE_PRODUCT, PAGE_BATTERY)

    /**
     * Request Data Page (common page 70 / 0x46): tells the device to transmit [requestedPage] now,
     * [txCount] times. Byte layout per the ANT+ common-pages spec (the Karoo uses the same shape,
     * see hxsensorservice q8/g.java): [70, 0xFF, 0xFF, descr1=0xFF, descr2=0xFF, txResponse,
     * requestedPage, commandType=0x01]. Descriptor bytes 0xFF = unfiltered (single-component sensor).
     */
    fun requestDataPage(requestedPage: Int, txCount: Int = 4): ByteArray = byteArrayOf(
        70,
        0xFF.toByte(),
        0xFF.toByte(),
        0xFF.toByte(),
        0xFF.toByte(),
        (txCount and 0x7F).toByte(),   // bit7=0 → device replies with broadcasts (not acknowledged)
        (requestedPage and 0xFF).toByte(),
        0x01,                          // command type 0x01 = "request data page"
    )

    /**
     * Manual zero-offset calibration request (ANT+ Bicycle Power calibration page 0x01, id 0xAA).
     * The meter answers on page 0x01 with id 0xAC (success, offset in bytes 6-7) or 0xAF (fail).
     */
    fun manualCalibration(): ByteArray = byteArrayOf(
        0x01,
        0xAA.toByte(),
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
    )
}
