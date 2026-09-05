package com.enderthor.kpower.vdevice

import com.enderthor.kpower.data.BikePosition
import com.enderthor.kpower.data.TreadType
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/** Constante específica del aire seco, J/(kg·K). */
private const val R_AIR = 287.05

/** Temperatura por defecto cuando no hay dato real, en °C. */
private const val DEFAULT_TEMP_C = 15.0

/**
 * Densidad del aire (kg/m³) por la ley de gases ideales `ρ = P / (R·T)`.
 *
 * - [pressurePa]: presión en Pa; si es null se deriva de la altitud con la
 *   fórmula barométrica (equivalente al comportamiento anterior).
 * - [tempC]: temperatura en °C; si es null se asume [DEFAULT_TEMP_C].
 * - [elevation]: altitud en metros (solo se usa para el fallback de presión).
 */
fun airDensity(pressurePa: Double?, tempC: Double?, elevation: Double): Double {
    val pressure = pressurePa ?: (101325.0 * exp(-0.00011856 * elevation))
    val tempK = (tempC ?: DEFAULT_TEMP_C) + 273.15
    return pressure / (R_AIR * tempK)
}

/**
 * Área frontal (m²) estimada con la regresión de Bassett et al. (1999) para
 * ciclistas de carretera: A = 0.0293·altura(m)·masa(kg)^0.425 + 0.0604, escalada
 * por la posición. Devuelve 0.0 si la entrada no es válida.
 */
fun estimateFrontalArea(heightCm: Double, weightKg: Double, position: BikePosition): Double {
    if (heightCm <= 0.0 || weightKg <= 0.0) return 0.0
    val heightM = heightCm / 100.0
    val aBase = 0.0293 * heightM * weightKg.pow(0.425) + 0.0604
    return aBase * position.areaScale
}

/**
 * Crr aproximado a partir del neumático, sobre firme de referencia.
 * - base por dibujo del neumático.
 * - penalización suave por desviarse de una presión "de referencia" que
 *   escala con el ancho (anchos mayores → menor presión óptima).
 * El factor de superficie del perfil escala este Crr en el modelo físico.
 */
/**
 * Normaliza el ancho de neumático a mm. Las ruedas de carretera/gravel se dan en mm
 * (23–50) y las de MTB en pulgadas (1.9–2.6). Un valor ≤ 5 se interpreta como pulgadas
 * y se convierte (×25.4); en caso contrario se asume que ya está en mm.
 */
fun tyreWidthToMm(width: Double): Double =
    if (width > 0.0 && width <= 5.0) width * 25.4 else width

fun estimateCrr(
    widthMm: Double,
    pressureBar: Double,
    treadType: TreadType,
    tubeless: Boolean = false,
    systemMassKg: Double = 85.0,
): Double {
    val base = treadType.baseCrr
    val w = widthMm.coerceIn(18.0, 70.0)
    // Reference (optimal) pressure ≈ 22.8·wheelLoad / width^1.55, anchored to Frank Berto's 15%-tyre-drop
    // tables: the optimum scales with LOAD and FALLS STEEPLY with width (not a shallow width-only line).
    // Anchors matching Berto: 25mm@40kg→~6.2 bar, 32mm→~4.2, 40mm→~3.0, 50mm→~2.1. wheelLoad ≈ ½ the
    // rider+bike mass (≈50/50). NOTE Berto runs a touch HIGH on narrow road tyres vs modern impedance
    // models (SILCA/Poertner) — field calibration refines it empirically. Upper clamp = the `p` clamp
    // (9 bar) so a heavy rider on a narrow tyre at a legitimately high pressure isn't falsely penalised.
    val wheelLoadKg = (systemMassKg.coerceIn(40.0, 150.0)) / 2.0
    val refPressure = (22.8 * wheelLoadKg / w.pow(1.55)).coerceIn(1.5, 9.0)
    val p = pressureBar.coerceIn(1.0, 9.0)
    // Real-road Crr-vs-pressure is U-shaped (impedance/vibration losses above the optimum, casing losses
    // below) — Silca/Poertner — so deviating EITHER way from the reference penalises. ~6% per bar.
    val penalty = 1.0 + 0.06 * abs(p - refPressure)
    // Tubeless rueda mejor que con cámara; el ahorro depende del tipo de neumático
    // (ver TreadType.tubelessFactor, basado en bicyclerollingresistance.com).
    val tubelessFactor = if (tubeless) treadType.tubelessFactor else 1.0
    return base * penalty * tubelessFactor
}

/**
 * Calcula la aceleración (m/s²) a partir de la velocidad muestreada.
 * - EMA para filtrar ruido del GPS.
 * - Clamp a ±[maxAccel] (fuera de rango = ruido).
 * - Velocidad ~0 (parado o GPS stale, que reemite 0.0) → reinicia y devuelve 0,
 *   evitando un pico de frenada falso.
 * - dt no positivo o mayor que [maxDtMs] (pausa) → reinicia y devuelve 0.
 * No usa reloj propio: el llamante pasa [nowMs] (testeable).
 */
class AccelerationTracker(
    private val emaAlpha: Double = 0.3,
    private val maxAccel: Double = 2.0,
    private val maxDtMs: Long = 5_000L,
    private val minSpeedMs: Double = 0.5,
) {
    private var prevSpeed = 0.0
    private var prevTs = 0L
    private var ema = 0.0

    fun update(speedMs: Double, nowMs: Long): Double {
        if (speedMs <= minSpeedMs) {
            prevTs = 0L
            ema = 0.0
            return 0.0
        }
        if (prevTs == 0L) {
            prevTs = nowMs
            prevSpeed = speedMs
            return 0.0
        }
        val dtMs = nowMs - prevTs
        if (dtMs <= 0L || dtMs > maxDtMs) {
            prevTs = nowMs
            prevSpeed = speedMs
            ema = 0.0
            return 0.0
        }
        val raw = ((speedMs - prevSpeed) / (dtMs / 1000.0)).coerceIn(-maxAccel, maxAccel)
        ema = emaAlpha * raw + (1 - emaAlpha) * ema
        prevTs = nowMs
        prevSpeed = speedMs
        return ema
    }
}

// NOTE: no hay suavizado propio de la potencia final. El sensor virtual emite la potencia
// INSTANTÁNEA (como un medidor real, cuyo broadcast ANT+ es instantáneo) y el Karoo aplica su
// propio suavizado de campo (3s/10s/…). Un EMA aquí (el antiguo PowerSmoother, τ=3s) suavizaba
// DOS veces: la estimación llegaba segundos tarde y nunca alcanzaba los picos.

/**
 * Suavizado de la pendiente (EMA, τ corto). El término de gravedad domina el modelo
 * (a 80 kg y 30 km/h, ±2 % de ruido de grade ≈ ±130 W), así que filtrar aquí ataca
 * los spikes en su origen. τ corto para no retrasar los cambios reales de rampa.
 * Gap mayor que [maxDtMs] (pausa / pérdida de stream) → re-siembra con el valor crudo.
 */
class GradeSmoother(
    private val tauMs: Double = 2_000.0,
    private val maxDtMs: Long = 10_000L,
) {
    private var ema = 0.0
    private var prevTs = 0L

    fun update(gradePercent: Double, nowMs: Long): Double {
        val dtMs = nowMs - prevTs
        if (prevTs == 0L || dtMs <= 0L || dtMs > maxDtMs) {
            ema = gradePercent
            prevTs = nowMs
            return ema
        }
        val alpha = 1.0 - exp(-dtMs / tauMs)
        ema += alpha * (gradePercent - ema)
        prevTs = nowMs
        return ema
    }

    /** Re-seed on the next sample, as if a gap had occurred. */
    fun reseed() { prevTs = 0L }
}

/**
 * Deriva la pendiente desde el stream de ALTITUD (fresco) en vez del `grade` del Karoo (ya suavizado
 * ~6 s). Validado sobre FIT real: grade desde altitud → corr 0,60→0,82 y retardo 8→2 s (con lead), muy
 * por encima del lead sobre el grade del Karoo. `grade% = 100·Δaltitud / Σ(velocidad·dt)` sobre una
 * ventana causal corta — integra la distancia desde la velocidad que el motor ya consume, así que NO
 * necesita un stream nuevo.
 *
 * Devuelve `null` cuando no puede fiarse (poco recorrido: parado / casi parado, o gap de stream) → el
 * llamante cae al `grade` del Karoo. El coste es algo más de ruido de pendiente (se suaviza con EMA
 * corto aquí y el lead/EMA de [GradeLeadCompensator] aguas abajo); en llano el ruido pesa más → validar.
 *
 * Sin reloj propio (el llamante pasa [nowMs]) → testeable y validable con el arnés offline del FIT.
 */
class AltitudeGradeDeriver(
    private val windowMs: Long = 4_000L,
    private val minTravelM: Double = 4.0,
    private val emaTauMs: Double = 2_000.0,
    private val maxDtMs: Long = 10_000L,
) {
    private companion object { const val MIN_SPEED_FOR_GRADE_MS = 0.5 }

    private data class Sample(val ts: Long, val cumDistM: Double, val altM: Double)
    private val history = ArrayDeque<Sample>()
    private var cumDistM = 0.0
    private var prevTs = 0L
    private var ema = 0.0
    private var seeded = false

    fun update(speedMs: Double, altitudeM: Double, nowMs: Long): Double? {
        val dtMs = nowMs - prevTs
        // Sin velocidad no hay distancia, pero la ALTITUD sigue llegando: si guardásemos esas muestras,
        // al volver la velocidad dividiríamos un desnivel real de varios segundos entre la distancia de
        // un solo tick → pendiente 3x la real y un pico de potencia en cada recuperación del GPS. Cubre
        // tanto el stream ausente (speed→0) como el centinela de velocidad obsoleta (también 0).
        if (speedMs < MIN_SPEED_FOR_GRADE_MS) {
            history.clear(); seeded = false; prevTs = nowMs
            return null
        }
        if (prevTs != 0L && (dtMs <= 0L || dtMs > maxDtMs)) {
            // Gap / pausa: la distancia integrada y la ventana ya no son continuas → reinicia.
            history.clear(); seeded = false
        }
        // Integra distancia desde la velocidad (deriva despreciable en una ventana de pocos segundos).
        if (prevTs != 0L && dtMs in 1..maxDtMs) cumDistM += speedMs * (dtMs / 1000.0)
        prevTs = nowMs
        history.addLast(Sample(nowMs, cumDistM, altitudeM))
        while (history.size > 1 && nowMs - history.first().ts > windowMs) history.removeFirst()

        val oldest = history.first()
        val travel = cumDistM - oldest.cumDistM
        if (travel < minTravelM) return null            // muy poco recorrido → pendiente no fiable
        val rawGrade = 100.0 * (altitudeM - oldest.altM) / travel
        if (!seeded) { ema = rawGrade; seeded = true } else {
            val alpha = 1.0 - exp(-dtMs / emaTauMs)
            ema += alpha * (rawGrade - ema)
        }
        return ema
    }
}

/**
 * Compensa el retardo del grade barométrico del Karoo (medido en campo: ~5 s del stream del Karoo +
 * ~3 s del pipeline). Sobre el EMA base ([GradeSmoother]) añade un término LEAD proporcional a la
 * derivada suavizada del grade: `grade_comp = ema + leadSeconds · d(ema)/dt`. Anticipa los cambios de
 * rampa sin tocar coeficientes ni física — el error del estimador era de TIMING, no de magnitud.
 *
 * - [tauMs] EMA base (1,5 s; el Karoo ya suaviza aguas arriba, no hace falta más).
 * - [leadSeconds] fuerza del lead (≈4 s = óptimo validado sobre FIT real: corr 0,60→0,71, retardo
 *   8→5 s, sin penalización de ruido; por encima de ~6 s el ruido empieza a ganar).
 * - [derivTauMs] la derivada se suaviza fuerte (~3 s) para NO amplificar el ruido de grade.
 * - [maxLeadPercent] acota el aporte del lead y [maxGradePercent] la salida, para que un salto de grade
 *   (túnel / pérdida de GPS) no dispare la potencia.
 * - Gap mayor que [maxDtMs] → re-siembra con el crudo (delega en [GradeSmoother]).
 *
 * Sin reloj propio (el llamante pasa [nowMs]) → testeable y validable con el arnés offline del FIT.
 */
class GradeLeadCompensator(
    tauMs: Double = 1_500.0,
    private val leadSeconds: Double = 4.0,
    private val derivTauMs: Double = 3_000.0,
    private val maxGradePercent: Double = 25.0,
    private val maxLeadPercent: Double = 8.0,
    private val maxDtMs: Long = 10_000L,
) {
    private val base = GradeSmoother(tauMs, maxDtMs)
    private var prevEma = 0.0
    private var derivEma = 0.0
    private var prevTs = 0L

    fun update(gradePercent: Double, nowMs: Long): Double {
        val ema = base.update(gradePercent, nowMs)
        val dtMs = nowMs - prevTs
        if (prevTs == 0L || dtMs <= 0L || dtMs > maxDtMs) {
            // Primer sample o gap: re-siembra la derivada, sin lead (igual que el EMA base re-sembrado).
            prevTs = nowMs; prevEma = ema; derivEma = 0.0
            return ema.coerceIn(-maxGradePercent, maxGradePercent)
        }
        val rawDeriv = (ema - prevEma) / (dtMs / 1000.0)     // %/s
        val alpha = 1.0 - exp(-dtMs / derivTauMs)
        derivEma += alpha * (rawDeriv - derivEma)
        prevTs = nowMs; prevEma = ema
        val lead = (leadSeconds * derivEma).coerceIn(-maxLeadPercent, maxLeadPercent)
        return (ema + lead).coerceIn(-maxGradePercent, maxGradePercent)
    }

    /**
     * Re-siembra la derivada ante una discontinuidad que NO es un hueco temporal: el corte del hold de
     * grade (que baja el valor retenido a 0 de golpe) o el regreso del stream tras un corte. Sin esto la
     * derivada lee ese ESCALÓN como un cambio de pendiente realísimo y añade un lead enorme —medido:
     * descenso fantasma de -2 % al expirar el hold y pico de +130 W al volver el stream—.
     *
     * Re-siembra TAMBIÉN el EMA base: solo con [prevTs] el escalón seguía rampando por el suavizador y
     * la derivada lo recogía a partir del segundo tick, dejando ~2/3 del artefacto y además hundiendo el
     * primer tick (~155 W por debajo de la verdad). Así es el mismo camino que un hueco real.
     */
    fun reseed() { prevTs = 0L; base.reseed() }
}

/**
 * Histéresis del gate de cadencia: pedaleo ON al superar [onRpm], OFF por debajo de
 * [offRpm]. Sustituye el umbral único (<22 rpm → 0 W), que hacía parpadear la potencia
 * entre 0 y el valor completo cuando la cadencia bailaba alrededor del corte.
 */
class CadenceGate(
    private val onRpm: Double = 25.0,
    private val offRpm: Double = 20.0,
) {
    private var pedaling = false

    fun update(cadenceRpm: Double): Boolean {
        pedaling = when {
            cadenceRpm >= onRpm -> true
            cadenceRpm <= offRpm -> false
            else -> pedaling
        }
        return pedaling
    }
}
