package com.enderthor.kpower.vdevice

import io.hammerhead.karooext.models.RideState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the autopause-wipes-session bug: resuming from a pause
 * (Recording -> Paused -> Recording, e.g. autopause at a traffic light) must NOT be
 * treated as a new ride. Reset must fire ONLY on the first Recording after a genuine
 * Idle (or on the very first Recording seen by a freshly-created instance).
 */
class RideResetGateTest {

    @Test
    fun `Idle then Recording resets once`() {
        val gate = RideResetGate()
        assertFalse(gate.onRideState(RideState.Idle))
        assertTrue(gate.onRideState(RideState.Recording))
        // A further Recording (no intervening Idle) must not reset again.
        assertFalse(gate.onRideState(RideState.Recording))
    }

    @Test
    fun `Recording then Paused then Recording does not reset`() {
        val gate = RideResetGate()
        assertTrue(gate.onRideState(RideState.Recording)) // initial arm consumed
        assertFalse(gate.onRideState(RideState.Paused(auto = true)))
        assertFalse(gate.onRideState(RideState.Recording)) // autopause resume: must NOT reset
    }

    @Test
    fun `repeated Paused Recording cycles never reset after the initial arm`() {
        val gate = RideResetGate()
        assertTrue(gate.onRideState(RideState.Recording)) // consumes the initial arm once
        repeat(20) {
            assertFalse(gate.onRideState(RideState.Paused(auto = true)))
            assertFalse(gate.onRideState(RideState.Recording))
        }
    }

    @Test
    fun `Recording then Idle then Recording resets again`() {
        val gate = RideResetGate()
        assertTrue(gate.onRideState(RideState.Recording))  // first ride starts
        assertFalse(gate.onRideState(RideState.Idle))       // ride ends
        assertTrue(gate.onRideState(RideState.Recording))  // genuine new ride starts
    }

    @Test
    fun `fresh instance with immediate Recording resets once (process restart mid-ride)`() {
        val gate = RideResetGate()
        assertTrue(gate.onRideState(RideState.Recording))
        assertFalse(gate.onRideState(RideState.Recording))
    }

    @Test
    fun `recording flag mirrors Recording state only`() {
        val gate = RideResetGate()
        assertFalse(gate.recording)
        gate.onRideState(RideState.Recording)
        assertTrue(gate.recording)
        gate.onRideState(RideState.Paused(auto = false))
        assertFalse(gate.recording)
        gate.onRideState(RideState.Recording)
        assertTrue(gate.recording)
        gate.onRideState(RideState.Idle)
        assertFalse(gate.recording)
    }
}
