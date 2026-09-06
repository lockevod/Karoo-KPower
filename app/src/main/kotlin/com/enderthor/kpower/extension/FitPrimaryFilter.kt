package com.enderthor.kpower.extension

/** Whether the estimate's `est_*` developer fields should be written to the FIT.
 *
 *  These fields exist for ONE purpose: capturing the model's estimate alongside the real meter for a
 *  post-ride comparison. That is an explicit, opt-in choice — the "comparison mode" toggle — so it is
 *  the ONLY input that decides the write.
 *
 *  [virtualDeviceConnected] is accepted on the signature DELIBERATELY, to make it explicit that it must
 *  NOT influence the result. Gating on it was the 2026-06-28 data-loss bug: the old logic suppressed
 *  `est_*` whenever KPower's virtual sensor was connected (assuming "connected" meant "the estimate is
 *  already the Karoo's recorded `power`, so writing it again would duplicate"). That assumption is false
 *  when the rider has KPW Estimated paired ALONGSIDE a real meter that is the Karoo's actual power
 *  source: native `power` is then the REAL meter (it even carries native dynamics), so suppressing
 *  `est_*` recorded the estimate nowhere and silently broke the comparison. Worst case of always writing
 *  is a harmless duplicate column when the estimate genuinely IS the bound source — far better than
 *  silent data loss. Keeping the parameter here (passed but ignored) documents the decision input and
 *  makes any future re-introduction of the gate fail the unit test. */
fun shouldWriteEstimateToFit(
    comparisonMode: Boolean,
    @Suppress("UNUSED_PARAMETER") virtualDeviceConnected: Boolean,
): Boolean = comparisonMode
