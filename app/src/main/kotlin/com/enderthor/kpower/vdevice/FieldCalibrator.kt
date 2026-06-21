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
 * Parameters: column 0 = CdA (shared); columns 1..N = Crr_eff per [KarooSurface]. We accumulate the full
 * KxK normal-equation matrix incrementally (no per-sample storage). At solve time only the columns with
 * enough samples are kept (a surface barely ridden is reported "insufficient data", not guessed), and
 * the reduced system is solved by Gaussian elimination with partial pivoting; a singular/ill-conditioned
 * system (e.g. no speed variation → aero & rolling collinear) yields null. Coefficients are derived from
 * the rider's OWN data — the only legitimate way to refine them.
 *
 * @Synchronized throughout so the 1 Hz accumulator and an off-thread reader can't tear the sums.
 */
class FieldCalibrator {
    private val surfaces = KarooSurface.entries           // column (1+index) per surface
    private val k = 1 + surfaces.size                     // column 0 = CdA
    private val a = Array(k) { DoubleArray(k) }           // Σ r·rᵀ
    private val rhs = DoubleArray(k)                      // Σ r·y
    private var syy = 0.0                                 // Σ y²
    private val countPerSurface = LongArray(surfaces.size)
    private var n = 0L

    /** [surface] = the surface the sample was ridden on; samples on an unrecognised surface are skipped. */
    @Synchronized
    fun add(y: Double, x1Rolling: Double, x2Aero: Double, surface: KarooSurface) {
        if (!y.isFinite() || !x1Rolling.isFinite() || !x2Aero.isFinite()) return
        val si = surfaces.indexOf(surface)
        if (si < 0) return
        val r = DoubleArray(k)
        r[0] = x2Aero
        r[1 + si] = x1Rolling
        for (i in 0 until k) {
            rhs[i] += r[i] * y
            val ri = r[i]
            if (ri != 0.0) for (j in 0 until k) a[i][j] += ri * r[j]
        }
        syy += y * y
        n++
        countPerSurface[si]++
    }

    @Synchronized
    fun reset() {
        for (i in 0 until k) { rhs[i] = 0.0; for (j in 0 until k) a[i][j] = 0.0 }
        syy = 0.0; n = 0L
        for (i in countPerSurface.indices) countPerSurface[i] = 0L
    }

    @Synchronized
    fun sampleCount(): Long = n

    @Synchronized
    fun result(): Fit? {
        if (n < MIN_SAMPLES) return null
        // Keep CdA (col 0) plus every surface column with enough samples.
        val cols = mutableListOf(0)
        for (si in surfaces.indices) if (countPerSurface[si] >= MIN_SAMPLES_PER_SURFACE) cols.add(1 + si)
        if (cols.size < 2) return null   // need aero + at least one surface
        val m = cols.size

        val ar = Array(m) { i -> DoubleArray(m) { j -> a[cols[i]][cols[j]] } }
        val br = DoubleArray(m) { i -> rhs[cols[i]] }
        val inv = invert(ar) ?: return null            // null = singular
        val sol = DoubleArray(m) { i -> (0 until m).sumOf { j -> inv[i][j] * br[j] } }
        if (sol.any { !it.isFinite() }) return null

        // Residual variance σ² = SSres/(n−m); coefficient std error_i = sqrt(σ²·(XᵀX)⁻¹_ii). The SE is
        // the HONEST identifiability metric: an ill-conditioned (e.g. steady-speed) ride yields a huge SE
        // even though SSres is tiny, so a "well-fit-looking" but unidentifiable coefficient is flagged.
        var ssRes = syy
        for (i in 0 until m) ssRes -= sol[i] * br[i]
        ssRes = ssRes.coerceAtLeast(0.0)
        val dof = (n - m).coerceAtLeast(1L)
        val sigma2 = ssRes / dof
        fun seOf(i: Int): Double { val v = sigma2 * inv[i][i]; return if (v > 0.0 && v.isFinite()) kotlin.math.sqrt(v) else Double.NaN }

        val cda = sol[0]
        val cdaSe = seOf(0)
        val perSurface = surfaces.mapIndexed { si, surf ->
            val colIdx = cols.indexOf(1 + si)
            if (colIdx >= 0) {
                val crr = sol[colIdx]; val se = seOf(colIdx)
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
            samples = n,
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
