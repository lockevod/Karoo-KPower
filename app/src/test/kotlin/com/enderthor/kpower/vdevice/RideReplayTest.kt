package com.enderthor.kpower.vdevice

import com.enderthor.kpower.data.KarooSurface
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Replay de una marcha REAL a través de la cadena de estimación de producción.
 *
 * Fixture: `ride-2026-09-06-mtb.csv`, exportado del FIT del Karoo de la salida del 2026-09-06
 * (MTB Spark, 24,9 km / 869 m, Garmin Rally 200 emparejado NATIVO por ANT+). La columna
 * `real_power_w` es el medidor real, así que sirve de verdad-terreno para la estimación.
 *
 * Esa salida no grabó `est_power` (el toggle de modo comparación estaba OFF), así que este replay
 * es la única forma de puntuar el algoritmo sobre ella — y de detectar regresiones sin salir a rodar.
 *
 * Reproduce el bucle de [PowerEstimationEngine] (el de `.sample(1 s)`; el FIT ya viene a 1 Hz).
 * NO cubre: grade de ruta (Fase 2 — sin navegación), viento (sin dato), clasificación de superficie
 * en vivo (sin tiles offline → cae al preset, que es lo que hace el motor sin datos de mapa).
 */
class RideReplayTest {

    // Config real del rider (kpower_bikes.json, bici "Spark") + perfil del Karoo.
    private companion object {
        const val BIKE_MASS = 14.4   // techo del rider: bici 11 + pedales 0,4 + bidones 1,2 + herram. 0,5 + ruedas ~1,3
        const val CRR = 0.0098
        const val CD = 0.90
        const val FRONTAL_AREA = 0.527
        const val POWER_LOSS = 0.025          // 2.5 %
        const val FTP = 232.0                 // threshold_power del perfil (useProfileFtp = true)
        val SURFACE = KarooSurface.GRAVEL     // preset; useRouteSurface sin datos vivos → preset

        // Peso del ciclista, del perfil del Karoo (confirmado por el rider: 69 kg).
        // OJO: es el peso DESNUDO del perfil. La masa que realmente sube la cuesta incluye
        // casco, zapatillas, ropa, bidones, mochila y herramientas — en MTB fácilmente +3..5 kg,
        // que el modelo no ve y aparecen como sesgo negativo (ver `sensibilidad al peso`).
        const val RIDER_MASS = 70.0

        // Espejo de las constantes privadas del motor.
        const val MIN_SPEED_FOR_POWER_MS = 0.8
        const val MIN_MOVING_TICKS = 2
        const val KAROO_TEMP_BIAS_C = 5.0
    }

    private data class Tick(
        val tMs: Long, val speed: Double?, val alt: Double?,
        val cadence: Double?, val tempC: Double?, val grade: Double?, val realW: Double?,
        // Meteo RECONSTRUIDA (no la que consumió el motor). Producción prefiere HEADWIND
        // (`preferHeadwind` es true por defecto, ConfigData.kt:148) y sólo cae a su propio
        // Open-Meteo si Headwind no está o no emite — y en el dispositivo del rider Headwind
        // SÍ estaba instalado durante esta marcha. Además producción conserva un snapshot
        // hasta moverse/caducar, mientras aquí se interpolan valores horarios por tick desde
        // un único centroide. La fórmula del frontal sí es la de `headwindFlow`:
        // ws * cos(wind_dir_from - rumbo), cuantizado a 0.1 m/s.
        // Es una banda de sensibilidad razonable, NO una ejecución de producción: escalar el
        // viento x0..x2 mueve el trabajo entre -5 % y +1 %. Ver tools/fit_to_replay_fixture.py.
        val headwind: Double?, val wxTempC: Double?, val wxPressureHpa: Double?,
    )

    private fun load(): List<Tick> {
        val txt = javaClass.classLoader!!.getResourceAsStream("ride-2026-09-06-mtb.csv")!!
            .bufferedReader().readLines()
        return txt.drop(1).filter { it.isNotBlank() }.map { line ->
            val c = line.split(",")
            fun d(i: Int) = c.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toDouble()
            Tick(c[0].toLong(), d(1), d(2), d(3), d(4), d(5), d(6), d(7), d(8), d(9))
        }
    }

    /** Devuelve la potencia estimada por tick, con la MISMA secuencia que el motor. */
    /**
     * Fuente de pendiente. LEGACY reproduce el código PRE-FIX (`48bd68f^`), que era un
     * `GradeSmoother` pelado sobre el grade del Karoo, SIN lead: es el único baseline honesto
     * para medir la ganancia del fix. KAROO_LEAD es el pipeline actual sin la fuente altitud
     * (aísla sólo la aportación del deriver, NO el fix completo).
     */
    private enum class GradeSrc { ALTITUDE, KAROO_LEAD, LEGACY }

    private fun replay(
        ticks: List<Tick>, riderMass: Double, gradeSrc: GradeSrc = GradeSrc.ALTITUDE,
        calibrator: FieldCalibrator? = null, powerLoss: Double = POWER_LOSS,
        // null = los de producción para la fuente de altitud (PowerEstimationEngine.kt:117).
        leadSeconds: Double? = null, tauMs: Double? = null,
        // Si se pasa, recibe la pendiente REALMENTE USADA por tick. Necesario para agrupar por
        // terreno: agrupar por el grade crudo del Karoo (~6 s tarde) mientras la estimación se
        // calculó con el de altitud asigna muestras al cubo equivocado, y como la potencia
        // correlaciona con la pendiente, eso infla el error aparente de bajada y llano.
        slopesOut: DoubleArray? = null,
    ): DoubleArray {
        val accelerationTracker = AccelerationTracker()
        val gradeHold = GradeHold()
        // Por defecto, los parámetros EXACTOS de producción (PowerEstimationEngine.kt:117) en el
        // brazo de altitud y (1500, 4) en los brazos que leen el grade del Karoo — que es como los
        // sintonizó cada ruta en su momento. OJO: dar a cada brazo un lead distinto CONFUNDE el A/B
        // (cambian dos variables a la vez), así que el A/B también publica la comparación a lead
        // IGUALADO, que es la limpia. Ver `A/B de fuente a lead igualado`.
        val gradeCompensator = GradeLeadCompensator(
            tauMs = tauMs ?: if (gradeSrc == GradeSrc.ALTITUDE) 1_000.0 else 1_500.0,
            leadSeconds = leadSeconds ?: if (gradeSrc == GradeSrc.ALTITUDE) 2.0 else 4.0,
        )
        val altitudeGradeDeriver = AltitudeGradeDeriver()
        val cadenceGate = CadenceGate()
        // LEGACY = `GradeSmoother()` por defecto, idéntico a 48bd68f^:111 (verificado en git).
        val legacySmoother = GradeSmoother()

        var movingTicks = 0
        var lastGoodElevation = 0.0
        val out = DoubleArray(ticks.size)

        ticks.forEachIndexed { i, t ->
            val nowMs = t.tMs
            val speedMs = t.speed ?: 0.0
            if (t.speed != null) {
                if (speedMs >= MIN_SPEED_FOR_POWER_MS) {
                    if (movingTicks < MIN_MOVING_TICKS) movingTicks++
                } else movingTicks = 0
            }
            val moving = movingTicks >= MIN_MOVING_TICKS
            val acceleration = accelerationTracker.update(speedMs, nowMs)

            val reseedForHold = gradeHold.update(
                streaming = t.grade != null, streamValue = t.grade ?: 0.0, nowMs = nowMs,
            )
            val lastGoodSlope = gradeHold.slopePercent
            if (t.alt != null) lastGoodElevation = t.alt
            val heldElevation = lastGoodElevation

            val altGrade = if (gradeSrc == GradeSrc.ALTITUDE && t.alt != null)
                altitudeGradeDeriver.update(speedMs, heldElevation, nowMs) else null
            if (reseedForHold || gradeHold.noteAltGradeAvailable(altGrade != null))
                gradeCompensator.reseed()
            val compensated = gradeCompensator.update(altGrade ?: lastGoodSlope, nowMs)
            val slopePercent = if (gradeSrc == GradeSrc.LEGACY)
                legacySmoother.update(lastGoodSlope, nowMs) else compensated
            slopesOut?.set(i, slopePercent)

            val isPedaling = if (t.cadence != null) cadenceGate.update(t.cadence) else moving

            val est = CyclingWattageEstimator(
                slope = slopePercent / 100,
                totalMass = riderMass + BIKE_MASS,
                rollingResistanceCoefficient = CRR,
                dragCoefficient = CD,
                frontalArea = FRONTAL_AREA,
                speed = speedMs,
                windSpeed = t.headwind ?: 0.0,
                powerLoss = powerLoss,
                elevation = heldElevation,
                ftp = FTP,
                isPedaling = isPedaling,
                surface = SURFACE.factor,
                isforcepower = false,
                // Temperatura y presión de Open-Meteo, como production (`bundle.weatherTempC` /
                // `weatherPressureHpa` son la PRIMERA opción, independientes de useKarooTemp).
                // Ojo: con presión no nula airDensity IGNORA la altitud (PhysicsModels.kt:24), así
                // que production aplica la presión de un punto a toda la marcha (50-433 m aquí).
                // Se replica el comportamiento real, no el ideal.
                temperatureC = t.wxTempC,
                pressurePa = t.wxPressureHpa?.times(100.0),
                acceleration = acceleration,
            )
            out[i] = maxOf(0.0, est.calculateCyclingWattage())
            // Alimenta el calibrador de campo con la potencia REAL, igual que hace el motor
            // (engine.realPowerProvider) cuando hay un medidor activo.
            // D6: production sólo alimenta el calibrador con pendiente FRESCA — un grade
            // retenido (stale) sesga el residuo (PowerEstimationEngine.kt:466-472).
            val gradeFresh = altGrade != null || t.grade != null
            if (calibrator != null && t.realW != null && gradeFresh) {
                est.calibrationRegressors(t.realW)?.let { (y, x1, x2) ->
                    calibrator.add(y, x1, x2, SURFACE)
                }
            }
        }
        return out
    }

    private fun np(s: List<Double>): Double {
        if (s.size < 30) return 0.0
        val r = (29 until s.size).map { i -> s.subList(i - 29, i + 1).average() }
        return r.sumOf { it.pow(4) }.div(r.size).pow(0.25)
    }

    private fun corr(a: List<Double>, b: List<Double>): Double {
        val ma = a.average(); val mb = b.average()
        val cov = a.indices.sumOf { (a[it] - ma) * (b[it] - mb) }
        val sa = sqrt(a.sumOf { (it - ma).pow(2) }); val sb = sqrt(b.sumOf { (it - mb).pow(2) })
        return if (sa == 0.0 || sb == 0.0) 0.0 else cov / (sa * sb)
    }

    @Test
    fun `replay de la marcha real contra el medidor`() {
        val ticks = load()
        val est = replay(ticks, RIDER_MASS)

        val idx = ticks.indices.filter { ticks[it].realW != null }
        val real = idx.map { ticks[it].realW!! }
        val model = idx.map { est[it] }
        val err = real.indices.map { model[it] - real[it] }
        val bias = err.average()
        val rmse = sqrt(err.sumOf { it * it } / err.size)

        fun smooth(s: List<Double>) = (29 until s.size).map { i -> s.subList(i - 29, i + 1).average() }
        val r30 = smooth(real); val m30 = smooth(model)
        val e30 = r30.indices.map { m30[it] - r30[it] }

        println("=== REPLAY 2026-09-06 (rider ${RIDER_MASS} kg + bici $BIKE_MASS kg) ===")
        println("  n=${idx.size}")
        println("  real  avg=%.1f W  NP=%.0f W  trabajo=%.0f kJ".format(real.average(), np(real), real.sum() / 1000))
        println("  est   avg=%.1f W  NP=%.0f W  trabajo=%.0f kJ  (%+.1f %%)".format(
            model.average(), np(model), model.sum() / 1000, 100.0 * model.sum() / real.sum() - 100.0))
        println("  1 s : sesgo=%+.1f W  RMSE=%.1f W  r=%.3f".format(bias, rmse, corr(real, model)))
        println("  30 s: sesgo=%+.1f W  RMSE=%.1f W  r=%.3f".format(
            e30.average(), sqrt(e30.sumOf { it * it } / e30.size), corr(r30, m30)))

        // Umbrales de regresión: holgados a propósito — vigilan que un cambio del algoritmo no
        // degrade la marcha real, no clavan el resultado actual. Apretar cuando se afine.
        assert(abs(bias) < 25.0) { "sesgo fuera de rango: $bias W" }
        assert(corr(r30, m30) > 0.90) { "correlación a 30 s degradada: ${corr(r30, m30)}" }
    }

    /** A/B de la Fase 1: grade derivado de la altitud vs el grade (retrasado) del Karoo. */
    @Test
    fun `la cadena de altitud mejora sobre el grade del Karoo`() {
        val ticks = load()
        val idx = ticks.indices.filter { ticks[it].realW != null }
        val real = idx.map { ticks[it].realW!! }
        fun smooth(s: List<Double>) = (29 until s.size).map { i -> s.subList(i - 29, i + 1).average() }
        val r30 = smooth(real)

        println("=== A/B fuente de pendiente (rider ${RIDER_MASS} kg) ===")
        val scores = listOf(GradeSrc.ALTITUDE, GradeSrc.KAROO_LEAD, GradeSrc.LEGACY).map { src ->
            val e = replay(ticks, RIDER_MASS, src)
            val model = idx.map { e[it] }
            val err = real.indices.map { model[it] - real[it] }
            val m30 = smooth(model)
            val rmse = sqrt(err.sumOf { it * it } / err.size)
            val r1 = corr(real, model); val r2 = corr(r30, m30)
            val label = when (src) {
                GradeSrc.ALTITUDE   -> "altitud (actual)"
                GradeSrc.KAROO_LEAD -> "Karoo + lead"
                GradeSrc.LEGACY     -> "PRE-FIX (48bd68f^)"
            }
            println("  %-19s sesgo=%+6.1f W  RMSE=%5.1f W  r(1s)=%.3f  r(30s)=%.3f".format(
                label, err.average(), rmse, r1, r2))
            Triple(rmse, r1, r2)
        }
        val (alt, karooLead, legacy) = Triple(scores[0], scores[1], scores[2])
        println("  -> fix COMPLETO vs pre-fix: RMSE %.1f -> %.1f W (%+.1f %%), r(1s) %.3f -> %.3f".format(
            legacy.first, alt.first, 100.0 * alt.first / legacy.first - 100.0, legacy.second, alt.second))
        // La aportación de la FUENTE, a lead igualado (misma tau y mismo lead en ambos brazos).
        // Comparar altitud@(1000,2) contra Karoo@(1500,4) mezclaría fuente y sintonía.
        for ((tau, lead) in listOf(1_000.0 to 2.0, 1_500.0 to 4.0)) {
            val a = idx.map { replay(ticks, RIDER_MASS, GradeSrc.ALTITUDE, leadSeconds = lead, tauMs = tau)[it] }
            val k = idx.map { replay(ticks, RIDER_MASS, GradeSrc.KAROO_LEAD, leadSeconds = lead, tauMs = tau)[it] }
            fun rmse(m: List<Double>) = sqrt(real.indices.sumOf { (m[it] - real[it]).pow(2) } / real.size)
            println("  -> fuente, a lead igualado (tau=%.0f L=%.0f): Karoo %.1f -> altitud %.1f W (%+.1f %%)".format(
                tau, lead, rmse(k), rmse(a), 100.0 * rmse(a) / rmse(k) - 100.0))
        }
        assert(alt.first < legacy.first) { "el fix NO mejora el RMSE sobre pre-fix" }
        assert(alt.second > legacy.second) { "el fix NO mejora la correlación sobre pre-fix" }
    }

    /**
     * Desviación PORCENTUAL respecto al medidor real: agregados de marcha y, sobre todo, la
     * distribución del error en ventanas — que es lo que de verdad nota el rider (un % sobre
     * muestras de 1 s no significa nada cuando la mitad son ceros de bajada).
     */
    @Test
    fun `desviacion porcentual respecto al medidor real`() {
        val ticks = load()
        val idx = ticks.indices.filter { ticks[it].realW != null }
        val real = idx.map { ticks[it].realW!! }

        // 69 kg = perfil tal cual. 74 kg = perfil + ~5 kg de equipo (casco, ropa, bidones, mochila),
        // que es la masa que realmente sube la cuesta.
        for (mass in listOf(RIDER_MASS, RIDER_MASS + 5.0)) {
            val slopes = DoubleArray(ticks.size)
            val e = replay(ticks, mass, slopesOut = slopes)
            val model = idx.map { e[it] }
            val tag = if (mass == RIDER_MASS) "perfil ${mass.toInt()} kg" else "${mass.toInt()} kg (+equipo)"
            println("=== desviacion % — $tag ===")
            println("  trabajo total : %+.1f %%   (%.0f vs %.0f kJ)".format(
                100.0 * model.sum() / real.sum() - 100.0, model.sum() / 1000, real.sum() / 1000))
            println("  potencia media: %+.1f %%   (%.1f vs %.1f W)".format(
                100.0 * model.average() / real.average() - 100.0, model.average(), real.average()))
            println("  NP            : %+.1f %%   (%.0f vs %.0f W)".format(
                100.0 * np(model) / np(real) - 100.0, np(model), np(real)))

            // Ventanas de 60 s con esfuerzo real (>= 50 W): distribución del error relativo.
            val win = 60
            val pct = (win until real.size step win).mapNotNull { i ->
                val r = real.subList(i - win, i).average()
                val m = model.subList(i - win, i).average()
                if (r >= 50.0) 100.0 * m / r - 100.0 else null
            }.sorted()
            fun q(f: Double) = pct[((pct.size - 1) * f).toInt()]
            println("  ventanas de 60 s con esfuerzo (n=${pct.size}):")
            println("    mediana %+.1f %%   P25 %+.1f %%   P75 %+.1f %%   P10 %+.1f %%   P90 %+.1f %%".format(
                q(0.5), q(0.25), q(0.75), q(0.10), q(0.90)))
            println("    dentro de ±10 %%: %.0f %%   dentro de ±20 %%: %.0f %%".format(
                100.0 * pct.count { abs(it) <= 10 } / pct.size,
                100.0 * pct.count { abs(it) <= 20 } / pct.size))
            // Gate revert-sensitive: sin esto la suite sigue verde aunque el estimador se
            // degrade. Holgado a propósito (la meteo reconstruida ya vale ~1 %), pero atrapa
            // una regresión de escala real.
            if (mass == RIDER_MASS) {
                val dev = 100.0 * model.sum() / real.sum() - 100.0
                assert(abs(dev) < 8.0) { "desviacion de trabajo fuera de rango: $dev %" }
            }

            // DESGLOSE POR TERRENO. El agregado es una media ponderada dominada por la subida
            // (~91 % de la energía), así que por sí solo no dice dónde falla el modelo.
            // Se agrupa por la pendiente REALMENTE USADA, no por el grade crudo del Karoo:
            // agrupar por el crudo (6 s tarde) daba bajada −32 % / subidas −0,5 %, que es un
            // ARTEFACTO de asignación de cubo. Con la pendiente usada el déficit está mucho más
            // repartido y las subidas cargan la mayor parte de él.
            println("  por TERRENO (energía, agrupado por la pendiente USADA):")
            for ((lo, hi, lbl) in listOf(
                Triple(-99.0, -2.0, "bajada < -2 %"), Triple(-2.0, 2.0, "llano -2..2 %"),
                Triple(2.0, 6.0, "subida 2-6 %"), Triple(6.0, 99.0, "subida > 6 %"),
            )) {
                val sel = idx.indices.filter { slopes[idx[it]] >= lo && slopes[idx[it]] < hi }
                if (sel.size < 30) continue
                val rk = sel.sumOf { real[it] } / 1000.0
                val mk = sel.sumOf { model[it] } / 1000.0
                println("    %-16s n=%5d  real=%6.1f kJ  est=%6.1f kJ  -> %+6.1f %%  (deficit %+5.1f kJ)".format(
                    lbl, sel.size, rk, mk, 100.0 * mk / rk - 100.0, mk - rk))
            }

            // Por banda de esfuerzo real (media de 30 s, para no puntuar ruido de 1 s).
            fun sm(s: List<Double>) = (29 until s.size).map { i -> s.subList(i - 29, i + 1).average() }
            val r30 = sm(real); val m30 = sm(model)
            println("  por banda de potencia real (medias de 30 s):")
            for ((lo, hi) in listOf(0.0 to 50.0, 50.0 to 150.0, 150.0 to 250.0, 250.0 to 1e9)) {
                val sel = r30.indices.filter { r30[it] >= lo && r30[it] < hi }
                if (sel.size < 30) continue
                val rr = sel.sumOf { r30[it] } / sel.size
                val mm = sel.sumOf { m30[it] } / sel.size
                val lbl = if (hi > 1e8) ">${lo.toInt()} W" else "${lo.toInt()}-${hi.toInt()} W"
                println("    %-10s n=%5d  real=%6.1f  est=%6.1f  -> %+.1f %%".format(
                    lbl, sel.size, rr, mm, 100.0 * mm / rr - 100.0))
            }
        }
    }

    /**
     * Corre el FieldCalibrator REAL sobre la marcha: es exactamente lo que `logCalibration` habría
     * escrito en el log de diagnóstico durante la salida. Ajusta CdA y el Crr efectivo por superficie
     * a partir de la potencia real, así que dice si los coeficientes cuadran con la masa declarada.
     */
    @Test
    fun `calibracion de campo sobre la marcha real`() {
        val ticks = load()
        // Barrido de masa: el ajuste no puede separar masa de Crr por sí solo (ambos entran en el
        // término de rodadura), PERO el término aero NO depende de la masa. Así que si al subir la
        // masa el Crr ajustado vuelve al configurado, el problema era la masa; si se queda alto,
        // es el neumático/terreno.
        for (rm in listOf(RIDER_MASS, RIDER_MASS + 5.0)) {
            val cal = FieldCalibrator()
            replay(ticks, rm, calibrator = cal)
            val fit = cal.result()
            println("=== calibracion de campo (masa declarada ${(rm + BIKE_MASS).toInt()} kg) ===")
            if (fit == null) { println("  sin ajuste (muestras: ${cal.sampleCount()})"); continue }
            printFit(fit)
        }
    }

    private fun printFit(fit: FieldCalibrator.Fit) {
        run {
            println("  muestras=${fit.samples}")
        println("  CdA ajustado = %.3f ± %.3f  (fiable=%b)   |  configurado = %.3f".format(
            fit.cda, fit.cdaSe, fit.cdaReliable, CD * FRONTAL_AREA))
        for (sc in fit.perSurface) {
            val cfg = CRR * SURFACE.factor
            println("  Crr_eff %-8s = %s ± %s  (n=%d, suficiente=%b, fiable=%b)  |  configurado = %.5f".format(
                sc.surface.name,
                sc.crrEff?.let { "%.5f".format(it) } ?: "—",
                sc.crrSe?.let { "%.5f".format(it) } ?: "—",
                sc.samples, sc.sufficient, sc.reliable, cfg))
            if (sc.crrEff != null) println("     -> ratio ajustado/configurado = %.2f".format(sc.crrEff!! / cfg))
        }
        println("  -> ratio CdA ajustado/configurado = %.2f".format(fit.cda / (CD * FRONTAL_AREA)))
        }
    }

    /**
     * Masa vs pérdidas de transmisión. El rider ha acotado la masa: bici 11 + pedales 0,4 +
     * bidones 1,2 + herramientas 0,5 + ruedas ~1,3 = 14,4 kg COMO MÁXIMO, más 1 kg de casco,
     * teléfono y vestimenta sobre los 69 kg de báscula. Techo real = 70 + 14,4 = 84,4 kg,
     * que es el límite superior que barre este test.
     *
     * Si con ese techo sigue faltando potencia, no es masa: es pérdida que el modelo de Martin
     * no tiene (transmisión sucia de MTB, y sobre todo el trabajo que se traga la suspensión y
     * el terreno roto). `powerLoss` es el único parámetro que representa eso, y 2,5 % es un
     * valor de carretera.
     */
    @Test
    fun `masa vs perdidas de transmision`() {
        val ticks = load()
        val idx = ticks.indices.filter { ticks[it].realW != null }
        val real = idx.map { ticks[it].realW!! }
        println("=== masa real acotada vs powerLoss (%% de desviacion en trabajo) ===")
        println("  masa total |  2,5 %%    4,0 %%    5,0 %%    6,0 %%    7,0 %%")
        for (rm in listOf(69.0, 70.0)) {
            val row = StringBuilder("  %5.1f kg   |".format(rm + BIKE_MASS))
            for (pl in listOf(0.025, 0.040, 0.050, 0.060, 0.070)) {
                val e = replay(ticks, rm, powerLoss = pl)
                val model = idx.map { e[it] }
                row.append(" %+6.1f  ".format(100.0 * model.sum() / real.sum() - 100.0))
            }
            println(row)
        }
        println("  (69 kg = bascula; 70 kg = + casco, telefono y vestimenta. bici = $BIKE_MASS kg)")

        // ¿Un multiplicador global rompe las subidas? Se mide con el agrupamiento CORRECTO
        // (pendiente usada). Con el bucketing viejo la respuesta parecia un si rotundo; hay
        // que comprobarlo, porque las subidas quedan a -1,3/-2,2 % y eso deja margen.
        println("  efecto en el TERRENO (masa ${(RIDER_MASS + BIKE_MASS)} kg):")
        for (pl in listOf(0.025, 0.050)) {
            val slopes = DoubleArray(ticks.size)
            val e = replay(ticks, RIDER_MASS, powerLoss = pl, slopesOut = slopes)
            val model = idx.map { e[it] }
            val parts = listOf(
                Triple(-99.0, -2.0, "bajada"), Triple(-2.0, 2.0, "llano"),
                Triple(2.0, 6.0, "sub2-6"), Triple(6.0, 99.0, "sub>6"),
            ).mapNotNull { (lo, hi, lbl) ->
                val sel = idx.indices.filter { slopes[idx[it]] >= lo && slopes[idx[it]] < hi }
                if (sel.size < 30) null else {
                    val r = sel.sumOf { real[it] }; val m = sel.sumOf { model[it] }
                    "%s %+.1f %%".format(lbl, 100.0 * m / r - 100.0)
                }
            }
            println("    powerLoss %.1f %% -> total %+.1f %%  |  %s".format(
                pl * 100, 100.0 * model.sum() / real.sum() - 100.0, parts.joinToString("  ")))
        }
    }

    /** El peso del perfil no está en el FIT: barrido para ver cuánto depende el resultado de él. */
    @Test
    fun `sensibilidad al peso del ciclista`() {
        val ticks = load()
        val idx = ticks.indices.filter { ticks[it].realW != null }
        val real = idx.map { ticks[it].realW!! }
        println("=== sensibilidad al peso ===")
        for (m in listOf(60.0, 65.0, 70.0, 72.0, 75.0, 80.0, 85.0)) {
            val e = replay(ticks, m)
            val model = idx.map { e[it] }
            val err = real.indices.map { model[it] - real[it] }
            println("  rider %5.1f kg -> sesgo=%+6.1f W  RMSE=%5.1f W  trabajo=%+5.1f %%".format(
                m, err.average(), sqrt(err.sumOf { it * it } / err.size),
                100.0 * model.sum() / real.sum() - 100.0))
        }
    }
}
