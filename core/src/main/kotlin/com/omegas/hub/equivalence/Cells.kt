package com.omegas.hub.equivalence

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/** Constantes do cérebro (docs/spec/equivalence.md). Espelho exato de tools/equivalence/oracle.py. */
object Eq {
    const val STABLE_FRAMES = 3; const val STABLE_WINDOW_MS = 1200L; const val STABLE_RPM = 150.0; const val STABLE_MAP = 0.03; const val MIN_PETROL_MS = 1.0
    const val CELL_BAR = 0.02; const val MAP_MIN = 0.10; const val MAP_MAX = 1.10; const val CELLS = 50
    const val CELL_KEEP = 30; const val BAND_MATURE_COUNT = 6; const val PRIOR_MIN_N = 3
    const val DRIVE_RPM = 1000.0; const val DRIVE_TP_MS = 3.0
    const val MIN_SAMPLES_MEASURED = 4; const val TOL_FLOOR = 0.04; const val LIGHT = 0.08; const val CONTEST_WORSE = 0.04; const val CONTEST_MIN = 0.05
    const val OUTLIER_MIN_LOG = 0.05; const val MAD_K = 3.0; const val OUTLIER_PASSES = 3
    const val LAMBDA = 0.3; const val IRLS_ITER = 6; const val TUKEY_C = 4.685
    val MAX_STEP_LN = ln(1.15); const val MAX_ELASTICITY = 0.35; const val LOW_GUARD_MS = 3.5
    const val K_MIN = 0.60; const val K_MAX = 0xFFFF / 16384.0; const val Q14 = 16384.0

    fun cellOf(mapBar: Double): Int? {
        val i = floor((mapBar - MAP_MIN) / CELL_BAR + 1e-9).toInt()
        return if (i in 0 until CELLS) i else null
    }
    fun cellCenter(i: Int) = MAP_MIN + (i + 0.5) * CELL_BAR
}

/** Uma amostra estável: média de 3 quadros parecidos no mesmo combustível. */
data class Sample(val rpm: Double, val mapBar: Double, val petrolMs: Double, val gasMs: Double, val fuel: String, val atMs: Long)

/** Quadro mínimo que o cérebro consome (já em unidades físicas). */
data class Frame(val rpm: Double, val mapBar: Double, val petrolMs: Double, val gasMs: Double, val fuel: String, val tMs: Long)

/** 3 quadros seguidos, mesmo combustível, ≤1200 ms, rpm ≤150, MAP ≤0.03, gasolina ≥1 ms → amostra. */
class Stabilizer {
    private val buf = ArrayDeque<Frame>()
    fun feed(f: Frame): Sample? {
        if ((f.fuel != "GASOLINA" && f.fuel != "GNV") || f.petrolMs < Eq.MIN_PETROL_MS) { buf.clear(); return null }
        buf.addLast(f); while (buf.size > Eq.STABLE_FRAMES) buf.removeFirst()
        if (buf.size < Eq.STABLE_FRAMES) return null
        val b = buf.toList()
        if (b.any { it.fuel != b[0].fuel } || b.last().tMs - b.first().tMs > Eq.STABLE_WINDOW_MS) return null
        if (b.maxOf { it.rpm } - b.minOf { it.rpm } > Eq.STABLE_RPM) return null
        if (b.maxOf { it.mapBar } - b.minOf { it.mapBar } > Eq.STABLE_MAP + 1e-12) return null
        val s = Sample(b.sumOf { it.rpm } / 3, b.sumOf { it.mapBar } / 3, b.sumOf { it.petrolMs } / 3, b.sumOf { it.gasMs } / 3, b[0].fuel, b.last().tMs)
        buf.clear()
        return s
    }
}

/** Por combustível: últimos 30 ln(t) por célula de 0.02 bar, só quadros "dirigindo". */
class Cells {
    private val ln = Array(Eq.CELLS) { ArrayDeque<Double>() }
    fun add(s: Sample): Boolean {
        if (s.rpm < Eq.DRIVE_RPM || s.petrolMs < Eq.DRIVE_TP_MS) return false
        val i = Eq.cellOf(s.mapBar) ?: return false
        ln[i].addLast(ln(s.petrolMs)); while (ln[i].size > Eq.CELL_KEEP) ln[i].removeFirst()
        return true
    }
    fun count(i: Int) = ln[i].size
    fun mean(i: Int) = ln[i].sum() / ln[i].size
    fun dispersion(i: Int): Double {
        val n = ln[i].size
        if (n < 2) return 0.0
        val m = mean(i); return sqrt(ln[i].sumOf { (it - m) * (it - m) } / (n - 1))
    }
    fun total() = ln.sumOf { it.size }
}
