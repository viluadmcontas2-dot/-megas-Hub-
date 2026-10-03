package com.omegas.hub.equivalence

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

enum class PointState { SEM_DADOS, APRENDENDO, EQUIVALENTE, POBRE, RICO, EM_PROVA, CONFIRMADO, CONTESTADO }

/** Um dos 30 pontos da Curva K visto pelo cérebro (docs/spec/equivalence.md §5). */
data class EquivalencePoint(
    val index: Int, val axisMs: Double, val kCurrent: Double, val kTarget: Double?, val mixture: Double?,
    val tolerance: Double, val mapBar: Double?, val samples: Int, val usage: Double, val state: PointState,
)

data class Index(val value: Double?, val coverage: Double, val provisional: Boolean)

data class NextAction(val kind: String, val text: String, val enabled: Boolean = true, val point: Int? = null)

object Points {
    private val MEASURED = setOf(PointState.EQUIVALENTE, PointState.POBRE, PointState.RICO, PointState.CONFIRMADO, PointState.CONTESTADO, PointState.EM_PROVA)

    fun compute(axisMs: DoubleArray, kRaw: IntArray, gas: FittedCurve, petrol: FittedCurve, gasCells: Cells, written: Map<Int, Double>): List<EquivalencePoint> {
        val total = max(gasCells.total(), 1)
        return axisMs.indices.map { i ->
            val t = axisMs[i]; val k = kRaw[i] / Eq.Q14
            val m = if (t > 0) gas.inverse(ln(t)) else null
            var kTarget: Double? = null; var mixture: Double? = null; var tol = Eq.TOL_FLOOR
            var samples = 0; var usage = 0.0; var state = PointState.SEM_DADOS
            if (m != null) {
                val ci = Eq.cellOf(m)
                val tGas = petrol.at(m)
                if (ci != null) {
                    val lo = max(0, ci - 1); val hi = min(Eq.CELLS - 1, ci + 1)
                    samples = (lo..hi).sumOf { gasCells.count(it) }
                    usage = samples.toDouble() / total
                    tol = max(Eq.TOL_FLOOR, 2 * gas.dispersion[ci])
                }
                if (tGas != null && samples > 0) {
                    val kt = k * t / exp(tGas); kTarget = kt; mixture = kt / k - 1
                    state = if (samples < Eq.MIN_SAMPLES_MEASURED) PointState.APRENDENDO
                    else if (abs(mixture) <= tol) PointState.EQUIVALENTE else if (mixture > 0) PointState.POBRE else PointState.RICO
                }
            }
            val before = written[i]
            if (before != null && state != PointState.SEM_DADOS) {
                val mx = abs(mixture!!)
                state = if (samples < Eq.MIN_SAMPLES_MEASURED) PointState.EM_PROVA
                else if (mx <= tol) PointState.CONFIRMADO
                else if (mx > abs(before) + Eq.CONTEST_WORSE && mx > Eq.CONTEST_MIN) PointState.CONTESTADO
                else PointState.EM_PROVA
            }
            EquivalencePoint(i, t, k, kTarget, mixture, tol, m, samples, usage, state)
        }
    }

    fun index(points: List<EquivalencePoint>, hasReference: Boolean): Index? {
        if (!hasReference) return null
        val meas = points.filter { it.state in MEASURED }
        val use = meas.sumOf { it.usage }
        if (meas.isEmpty() || use <= 0) return Index(null, 0.0, true)
        val value = meas.filter { it.state == PointState.EQUIVALENTE || it.state == PointState.CONFIRMADO }.sumOf { it.usage } / use
        val cov = meas.size.toDouble() / points.size
        return Index(value, cov, cov < 0.4)
    }

    fun nextAction(points: List<EquivalencePoint>, hasReference: Boolean, maturePetrolBands: Int, operating: Boolean): NextAction {
        if (operating) return NextAction("AGUARDE", "Aguarde: gravando…")
        if (!hasReference) {
            val ok = maturePetrolBands >= 10
            return NextAction("CONGELAR", if (ok) "Congelar referência" else "Rode em gasolina até a ECU completar as zonas", ok)
        }
        points.firstOrNull { it.state == PointState.CONTESTADO }?.let { return NextAction("DESFAZER", "Desfazer a gravação do ponto ${it.index}", point = it.index) }
        val off = points.filter { it.state == PointState.POBRE || it.state == PointState.RICO }
        if (off.isNotEmpty()) {
            val worst = off.maxBy { abs(it.mixture!!) * it.usage }
            return NextAction("GRAVAR", "Gravar proposta (${off.size} pontos, maior ajuste ${pct(worst.mixture!!)})")
        }
        points.firstOrNull { it.state == PointState.EM_PROVA }?.let { return NextAction("RODAR", "Rode em GNV perto de ${ms(it.axisMs)} ms para confirmar") }
        points.firstOrNull { (it.state == PointState.SEM_DADOS || it.state == PointState.APRENDENDO) && it.axisMs in 3.0..12.0 }
            ?.let { return NextAction("RODAR", "Rode em GNV perto de ${ms(it.axisMs)} ms") }
        return NextAction("NADA", "Equivalente. Nada a fazer.")
    }

    /** "+7 %" como o Python: sinal sempre, zero casas, arredondamento half-even. */
    fun pct(m: Double): String { val v = Math.rint(m * 100).toInt(); return (if (v >= 0) "+" else "") + v + " %" }
    fun ms(t: Double): String = String.format(java.util.Locale.ROOT, "%.1f", t)
}
