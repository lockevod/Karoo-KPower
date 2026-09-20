package com.enderthor.kpower.vdevice

import io.hammerhead.karooext.models.RideState

/**
 * Shared RideState transition logic for the two places that must reset session accumulators
 * (the estimation engine's NP/avg/calibration and each ANT+ meter's NP) on a genuine new ride,
 * but FREEZE (keep accumulating from where they left off) across an autopause.
 *
 * The bug this guards against: gating the reset on "was I NOT already recording" treats
 * Paused -> Recording the same as Idle -> Recording, so autopause at every traffic light wiped
 * NP/avg/calibration. Reset must fire ONLY on the first Recording after a genuine Idle.
 *
 * Not thread-safe: both call sites (PowerEstimationEngine.onRideState, AntPowerManager.onRideState)
 * are @Synchronized, so this is used only under that lock.
 */
class RideResetGate {

    // Starts true so the very first Recording seen by a fresh instance resets once — this covers
    // a process restart mid-ride (no Idle was ever observed by this instance), mirroring the
    // sawIdle pattern already used by the battery alert in KpowerExtension.kt.
    private var armed = true

    /** True only while the last state fed in was Recording (freeze/accumulate gate). */
    var recording: Boolean = false
        private set

    /**
     * Feed the current RideState. Returns true exactly once per genuine new ride: the first
     * Recording after an Idle (or the first Recording ever seen by a fresh instance). Returns
     * false for Paused -> Recording (autopause resume) and for every other transition.
     */
    fun onRideState(state: RideState): Boolean = when (state) {
        is RideState.Recording -> {
            val shouldReset = armed
            armed = false
            recording = true
            shouldReset
        }
        is RideState.Idle -> {
            armed = true
            recording = false
            false
        }
        else -> { // Paused: freeze, don't re-arm — autopause must not wipe NP/avg/calibration
            recording = false
            false
        }
    }
}
