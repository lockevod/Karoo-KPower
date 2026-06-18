package com.enderthor.kpower.extension

/** The estimate's developer fields are omitted only when KPower's virtual device is currently the
 *  Karoo's bound power source (auto-detected) — then the estimate is already in the standard
 *  `power` stream and writing `est_*` would duplicate it. Dedup is fully automatic: there is no
 *  user-facing "primary source" choice (that dropdown was removed). A real meter recorded through
 *  KPower always writes its `pm*_` fields — native double-pairing is handled by the UI warning, not
 *  by config-driven suppression. */
fun writeEstimateFields(estimateIsPrimary: Boolean): Boolean = !estimateIsPrimary
