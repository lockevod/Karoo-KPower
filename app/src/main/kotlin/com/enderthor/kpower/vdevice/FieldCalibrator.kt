package com.enderthor.kpower.vdevice

import com.enderthor.kpower.data.KarooSurface
import kotlin.math.abs

/**
 * Online field calibration of the aerodynamic + rolling coefficients from REAL power-meter data, by
 * least squares against the Martin et al. (1998) power balance.
 *
 * The model is LINEAR in the unknowns. We fit ONE global CdA (aero is surface-independent) and ONE
 * EFFECTIVE Crr PER surface class. For each 1 Hz sample on surface s:
 *
 *     Y = CdA·X2 + Crr_eff[s]·X1
 *
 * where (see [CyclingWattageEstimator.calibrationRegressors]):
 *     Y  = P_real·(1−loss) − (gravity + dynamic-rolling + inertia) power
 *     X1 = m·g·cosθ·v               (its per-surface coefficient is the EFFECTIVE Crr = base Crr × factor)
 *     X2 = ½·ρ·v_rel·|v_rel|·v      (its coefficient is CdA = dragCoefficient·frontalArea)
 *
 * Parameters: ONE shared CdA + an effective Crr per [KarooSurface]. Because every sample touches exactly
 * two regressors (the shared aero X2 and its own surface's rolling X1), we accumulate compact PER-SURFACE
 * sums. At solve time we build the reduced normal-equation system from ONLY the surfaces with enough
 * samples — dropped surfaces are excluded ENTIRELY (their rows contribute to neither the aero column nor
 * the residual), so an under-sampled surface can't bias the solved CdA. The reduced system is solved via
 * Gauss-Jordan; a singular/ill-conditioned ride (e.g. constant speed → aero & rolling collinear) yields a
 * huge std error → flagged unreliable. Coefficients come from the rider's OWN data.
 *
 * @Synchronized throughout so the 1 Hz accumulator and an off-thread reader can't tear the sums.
 */
class FieldCalibrator {
    private val surfaces = KarooSurface.entries
    private val n = surfaces.size
    // Per-surface sufficient statistics (every sample = aero X2 + that surface's rolling X1):
    private val sAeroAero = DoubleArray(n)   // Σ x2²
    private val sAeroRoll = DoubleArray(n)   // Σ x2·x1
    private val sRollRoll = DoubleArray(n)   // Σ x1²
    private val sRhsAero = DoubleArray(n)    // Σ x2·y
    private val sRhsRoll = DoubleArray(n)    // Σ x1·y
    private val sYy = DoubleArray(n)         // Σ y²
    private val countPerSurface = LongArray(n)
    private var total = 0L

    /** [surface] = the surface the sample was ridden on; samples on an unrecognised surface are skipped. */
    @Synchronized
    fun add(y: Double, x1Rolling: Double, x2Aero: Double, surface: KarooSurface) {
        if (!y.isFinite() || !x1Rolling.isFinite() || !x2Aero.isFinite()) return
        val si = surfaces.indexOf(surface)
        if (si < 0) return
        sAeroAero[si] += x2Aero * x2Aero
        sAeroRoll[si] += x2Aero * x1Rolling
        sRollRoll[si] += x1Rolling * x1Rolling
        sRhsAero[si] += x2Aero * y
        sRhsRoll[si] += x1Rolling * y
        sYy[si] += y * y
        countPerSurface[si]++
        total++
    }

    @Synchronized
    fun reset() {
        for (i in 0 until n) {
            sAeroAero[i] = 0.0; sAeroRoll[i] = 0.0; sRollRoll[i] = 0.0
            sRhsAero[i] = 0.0; sRhsRoll[i] = 0.0; sYy[i] = 0.0; countPerSurface[i] = 0L
        }
        total = 0L
    }

    @Synchronized
    fun sampleCount(): Long = total

    @Synchronized
    fun result(): Fit? {
        // Surfaces with enough samples to fit their Crr; everything else is excluded from the fit.
        val kept = (0 until n).filter { countPerSurface[it] >= MIN_SAMPLES_PER_SURFACE }
        if (kept.isEmpty()) return null
        val nKept = kept.sumOf { countPerSurface[it] }
        if (nKept < MIN_SAMPLES) return null

        // Reduced system over [CdA] + [Crr per kept surface]. Different surfaces never co-occur in one
        // sample, so the rolling↔rolling cross-terms are 0 — the matrix is an arrowhead (aero couples to
        // each surface; surfaces don't couple to each other).
        val m = 1 + kept.size
        val a = Array(m) { DoubleArray(m) }
        val b = DoubleArray(m)
        a[0][0] = kept.sumOf { sAeroAero[it] }
        b[0] = kept.sumOf { sRhsAero[it] }
        var syyKept = 0.0
        kept.forEachIndexed { idx, si ->
            val c = 1 + idx
            a[0][c] = sAeroRoll[si]; a[c][0] = sAeroRoll[si]
            a[c][c] = sRollRoll[si]
            b[c] = sRhsRoll[si]
            syyKept += sYy[si]
        }

        val inv = invert(a) ?: return null            // null = singular
        val sol = DoubleArray(m) { i -> (0 until m).sumOf { j -> inv[i][j] * b[j] } }
        if (sol.any { !it.isFinite() }) return null

        // σ² = SSres/(nKept−m); coefficient std error_i = sqrt(σ²·(XᵀX)⁻¹_ii) over the KEPT samples only —
        // the honest identifiability metric (ill-conditioned ride → huge SE → flagged unreliable).
        var ssRes = syyKept
        for (i in 0 until m) ssRes -= sol[i] * b[i]
        ssRes = ssRes.coerceAtLeast(0.0)
        val dof = (nKept - m).coerceAtLeast(1L)
        val sigma2 = ssRes / dof
        fun seOf(i: Int): Double { val v = sigma2 * inv[i][i]; return if (v > 0.0 && v.isFinite()) kotlin.math.sqrt(v) else Double.NaN }

        val cda = sol[0]
        val cdaSe = seOf(0)
        val perSurface = surfaces.mapIndexed { si, surf ->
            val idx = kept.indexOf(si)
            if (idx >= 0) {
                val crr = sol[1 + idx]; val se = seOf(1 + idx)
                SurfaceCrr(surf, crrEff = crr, crrSe = se, samples = countPerSurface[si],
                    sufficient = true, reliable = isReliable(crr, se, PLAUSIBLE_CRR_EFF))
            } else {
                SurfaceCrr(surf, crrEff = null, crrSe = null, samples = countPerSurface[si], sufficient = false, reliable = false)
            }
        }
        return Fit(
            cda = cda,
            cdaSe = cdaSe,
            cdaReliable = isReliable(cda, cdaSe, PLAUSIBLE_CDA),
            perSurface = perSurface,
            samples = nKept,
        )
    }

    /** A coefficient is trustworthy only if it's finite, in a sane range, AND its relative std error is
     *  small (well-identified by the ride — not dominated by collinearity/noise). */
    private fun isReliable(value: Double, se: Double, range: ClosedFloatingPointRange<Double>): Boolean =
        value.isFinite() && value in range && se.isFinite() && abs(value) > 0.0 && (se / abs(value)) <= MAX_REL_SE

    /** Invert a small square matrix by Gauss-Jordan with partial pivoting; null if singular. */
    private fun invert(matrix: Array<DoubleArray>): Array<DoubleArray>? {
        val m = matrix.size
        val aug = Array(m) { i -> DoubleArray(2 * m) { j -> if (j < m) matrix[i][j] else if (j - m == i) 1.0 else 0.0 } }
        for (col in 0 until m) {
            var piv = col
            for (r in col + 1 until m) if (abs(aug[r][col]) > abs(aug[piv][col])) piv = r
            if (abs(aug[piv][col]) < PIVOT_EPS) return null
            val tmp = aug[col]; aug[col] = aug[piv]; aug[piv] = tmp
            val d = aug[col][col]
            for (c in 0 until 2 * m) aug[col][c] /= d
            for (r in 0 until m) {
                if (r == col) continue
                val f = aug[r][col]
                if (f == 0.0) continue
                for (c in 0 until 2 * m) aug[r][c] -= f * aug[col][c]
            }
        }
        return Array(m) { i -> DoubleArray(m) { j -> aug[i][m + j] } }
    }

    /** One surface's result. [crrEff]/[crrSe] null + [sufficient]=false when too few samples on it. */
    data class SurfaceCrr(
        val surface: KarooSurface,
        val crrEff: Double?,
        val crrSe: Double?,
        val samples: Long,
        val sufficient: Boolean,
        val reliable: Boolean,
    )

    data class Fit(
        val cda: Double,
        val cdaSe: Double,
        val cdaReliable: Boolean,
        val perSurface: List<SurfaceCrr>,
        val samples: Long,
    )

    companion object {
        const val MIN_SAMPLES = 300L              // ~5 min of moving samples overall
        const val MIN_SAMPLES_PER_SURFACE = 120L  // ~2 min on a surface to fit its Crr
        private const val PIVOT_EPS = 1e-12
        // Max relative std error for a coefficient to be "reliable" (≤25% → reasonably identified).
        private const val MAX_REL_SE = 0.25
        // Sanity ranges (flag only, never alter). EFFECTIVE Crr already includes the surface factor, so
        // the span is wider than a base Crr (asphalt slick ~0.004 up to sand ~0.03+); CdA ~0.15..0.8.
        private val PLAUSIBLE_CRR_EFF = 0.002..0.05
        private val PLAUSIBLE_CDA = 0.15..0.80
    }
}
