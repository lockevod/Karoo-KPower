package com.enderthor.kpower.ant

/**
 * Parses ANT+ Bicycle Power data pages per Device Profile Rev 5.1 §17 (Cycling Dynamics).
 * Pure functions: ByteArray(8) -> model, or null on wrong page / too-short payload.
 * Every byte is masked `& 0xFF` before use; invalid sentinels map fields to null.
 */
object CyclingDynamicsParser {
    const val PAGE_POWER_ONLY = 0x10
    const val PAGE_WHEEL_TORQUE = 0x11
    const val PAGE_CRANK_TORQUE = 0x12
    const val PAGE_TE_PS = 0x13
    const val PAGE_TORQUE_BARYCENTER = 0x14
    const val PAGE_RIGHT_FORCE_ANGLE = 0xE0
    const val PAGE_LEFT_FORCE_ANGLE = 0xE1
    const val PAGE_PEDAL_POSITION = 0xE2
    const val PAGE_MANUFACTURER = 0x50   // common page 0x50: Manufacturer's Identification

    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF

    private fun bradsToDeg(raw: Int): Double = raw * 360.0 / 256.0

    fun parsePowerOnly(p: ByteArray): PowerOnlyData? {
        if (p.size < 8 || p.u(0) != PAGE_POWER_ONLY) return null
        val balRaw = p.u(2)
        val cad = p.u(3)
        val pwr = (p.u(7) shl 8) or p.u(6)
        return PowerOnlyData(
            eventCount = p.u(1),
            powerW = if (pwr == 0xFFFF) null else pwr.toDouble(),
            cadenceRpm = if (cad == 0xFF) null else cad.toDouble(),
            // b2 "Pedal Power": 0xFF invalid. Bit7 = right-pedal indicator; only when SET do
            // bits0-6 carry the right-pedal % (matching the original antpluginlib behaviour). If
            // bit7 is clear the balance is undetermined -> null, not a wrong-side value.
            balanceRightPct = if (balRaw == 0xFF || (balRaw and 0x80) == 0) null else (balRaw and 0x7F).toDouble(),
        )
    }

    fun parseTePs(p: ByteArray): TePsData? {
        if (p.size < 8 || p.u(0) != PAGE_TE_PS) return null
        fun pct(i: Int) = p.u(i).let { if (it == 0xFF) null else it * 0.5 }
        val psR = p.u(5)
        return TePsData(
            eventCount = p.u(1),
            teLeftPct = pct(2), teRightPct = pct(3),
            psLeftPct = pct(4),
            psRightPct = if (psR == 0xFF || psR == 0xFE) null else psR * 0.5,
            psCombined = psR == 0xFE,
        )
    }

    fun parseForceAngle(p: ByteArray, isLeft: Boolean): ForceAngleData? {
        val expected = if (isLeft) PAGE_LEFT_FORCE_ANGLE else PAGE_RIGHT_FORCE_ANGLE
        if (p.size < 8 || p.u(0) != expected) return null
        // Per spec §17 the 0xC0 sentinel means "invalid" ONLY when BOTH angles of a pair are
        // 0xC0. A lone 0xC0 is a legitimate 270° (192 brad). Same for the peak pair.
        fun pair(aIdx: Int, bIdx: Int): Pair<Double?, Double?> {
            val a = p.u(aIdx); val b = p.u(bIdx)
            return if (a == 0xC0 && b == 0xC0) null to null
            else bradsToDeg(a) to bradsToDeg(b)
        }
        val (start, end) = pair(2, 3)
        val (startPeak, endPeak) = pair(4, 5)
        val tq = (p.u(7) shl 8) or p.u(6)
        return ForceAngleData(
            isLeft = isLeft, eventCount = p.u(1),
            startAngleDeg = start, endAngleDeg = end,
            startPeakDeg = startPeak, endPeakDeg = endPeak,
            torqueNm = tq / 32.0,
        )
    }

    fun parsePedalPosition(p: ByteArray): PedalPositionData? {
        if (p.size < 8 || p.u(0) != PAGE_PEDAL_POSITION) return null
        val pos = when ((p.u(2) shr 6) and 0x03) {
            1 -> RiderPosition.TRANSITION_TO_SEATED
            2 -> RiderPosition.STANDING
            3 -> RiderPosition.TRANSITION_TO_STANDING
            else -> RiderPosition.SEATED
        }
        val cad = p.u(3)
        fun pco(i: Int) = p[i].toInt().let { if (it == -128) null else it }  // signed mm, -128 invalid
        return PedalPositionData(
            eventCount = p.u(1), riderPosition = pos,
            cadenceRpm = if (cad == 0xFF) null else cad.toDouble(),
            rightPcoMm = pco(4), leftPcoMm = pco(5),
        )
    }

    fun parseTorqueBarycenter(p: ByteArray): TorqueBarycenterData? {
        if (p.size < 2 || p.u(0) != PAGE_TORQUE_BARYCENTER) return null
        val raw = p.u(1)
        return TorqueBarycenterData(angleDeg = if (raw == 0xFF) null else raw * 0.5 + 30.0)
    }

    /** Common page 0x50: Manufacturer ID (b4-5 LE) + model number (b6-7 LE). Identity, not live data. */
    fun parseManufacturer(p: ByteArray): ManufacturerData? {
        if (p.size < 8 || p.u(0) != PAGE_MANUFACTURER) return null
        return ManufacturerData(
            manufacturerId = (p.u(5) shl 8) or p.u(4),
            modelNumber = (p.u(7) shl 8) or p.u(6),
        )
    }

    /** 0x11 wheel torque / 0x12 crank torque: raw accumulators (deltas give power, see [torquePower]). */
    fun parseTorque(p: ByteArray): TorqueData? {
        if (p.size < 8) return null
        val page = p.u(0)
        if (page != PAGE_WHEEL_TORQUE && page != PAGE_CRANK_TORQUE) return null
        val cad = p.u(3)
        return TorqueData(
            isCrank = page == PAGE_CRANK_TORQUE,
            eventCount = p.u(1),
            ticks = p.u(2),
            cadenceRpm = if (cad == 0xFF) null else cad.toDouble(),
            accumPeriod = (p.u(5) shl 8) or p.u(4),
            accumTorque = (p.u(7) shl 8) or p.u(6),
        )
    }

    /**
     * Power/cadence/torque from the delta between two consecutive torque pages (ANT+ Bicycle Power
     * §13). 8-bit event count and 16-bit period/torque accumulators are masked so a rollover is a
     * normal positive delta. Returns null when no new rotation event occurred (Δevent == 0) or the
     * period didn't advance — the caller decides whether to hold the last value or coast to zero.
     *
     *   Power (W)    = 128π · Δtorque / Δperiod   (Δtorque raw 1/32 Nm, Δperiod raw 1/2048 s)
     *   Cadence(rpm) = 60 · 2048 · Δevent / Δperiod
     *   Torque (Nm)  = (Δtorque / 32) / Δevent
     */
    fun torquePower(prev: TorqueData, curr: TorqueData): TorquePower? {
        val dEvent = (curr.eventCount - prev.eventCount) and 0xFF
        val dPeriod = (curr.accumPeriod - prev.accumPeriod) and 0xFFFF
        val dTorque = (curr.accumTorque - prev.accumTorque) and 0xFFFF
        if (dEvent == 0 || dPeriod == 0) return null
        return TorquePower(
            powerW = 128.0 * Math.PI * dTorque / dPeriod,
            cadenceRpm = 60.0 * 2048.0 * dEvent / dPeriod,
            torqueNm = (dTorque / 32.0) / dEvent,
        )
    }
}
