package com.enderthor.kpower.extension

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitPrimaryFilterTest {
    // Regression (2026-06-28 ride): the estimate's est_* developer fields used to be gated on
    // `comparisonMode && !virtualDeviceConnected`. That silently dropped the estimate whenever the
    // rider had "KPW Estimated" paired ALONGSIDE a real meter that was the Karoo's recorded power —
    // native `power` was then the real meter (with native dynamics) and the estimate went NOWHERE.
    // The estimate's FIT fields are an explicit comparison-ride opt-in, so the ONLY input is
    // comparison mode. virtualDeviceConnected MUST NOT influence the result — these two tests lock
    // exactly that, so a future edit re-adding `&& !virtualDeviceConnected` fails here.
    @Test fun `estimate written in comparison mode regardless of virtual device connection`() {
        assertTrue(shouldWriteEstimateToFit(comparisonMode = true, virtualDeviceConnected = true))
        assertTrue(shouldWriteEstimateToFit(comparisonMode = true, virtualDeviceConnected = false))
    }

    @Test fun `estimate never written when comparison mode is off`() {
        assertFalse(shouldWriteEstimateToFit(comparisonMode = false, virtualDeviceConnected = true))
        assertFalse(shouldWriteEstimateToFit(comparisonMode = false, virtualDeviceConnected = false))
    }
}
