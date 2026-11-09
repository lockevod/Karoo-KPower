package com.enderthor.kpower.vdevice

import com.enderthor.kpower.data.GpsCoordinates

/**
 * "¿Se mueve la bici según la POSICIÓN GPS?" — evidencia de movimiento independiente de la velocidad,
 * para que el detector de velocidad congelada no anule un sensor de rueda cuantizado cuando no hay
 * sensor de cadencia. Cuenta como movimiento alejarse más de [minMoveM] del último ancla (el jitter
 * parado se queda dentro); vale durante [windowMs]. Con el GPS perdido (túnel) la posición se congela
 * y deja de valer, que es justo cuando la detección de stale debe actuar.
 *
 * [update] desde un solo colector; [isMoving] se puede leer desde otro hilo.
 */
class GpsMotion(private val minMoveM: Double = 3.0, private val windowMs: Long = 5_000L) {
    private var anchor: GpsCoordinates? = null
    @Volatile private var lastMoveMs = Long.MIN_VALUE

    fun update(lat: Double, lng: Double, nowMs: Long) {
        val here = GpsCoordinates(lat, lng)
        val a = anchor
        if (a == null) { anchor = here; return }
        if (a.distanceTo(here) * 1000.0 > minMoveM) {
            anchor = here
            lastMoveMs = nowMs
        }
    }

    fun isMoving(nowMs: Long): Boolean =
        lastMoveMs != Long.MIN_VALUE && nowMs - lastMoveMs <= windowMs
}
