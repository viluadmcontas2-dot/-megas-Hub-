package com.omegas.hub.equivalence

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Origin { MEDIDO, MISTO, SUAVIZADO, MANTIDO }

data class Proposal(val kRaw: IntArray, val origin: List<Origin>, val maxNeighborStep: Double, val maxElasticity: Double, val roughness: Double, val signChanges: Int)

/** Whittaker em ln K sobre u = ln t com IRLS Tukey; prior = K atual; restrições por projeção (oracle.propose). */
object Proposer {
    fun propose(axisMs: DoubleArray, kCurrent: DoubleArray, points: List<EquivalencePoint>): Proposal {
        val n = axisMs.size
        val u = DoubleArray(n) { ln(axisMs[it]) }
        val lnCur = DoubleArray(n) { ln(max(kCurrent[it], 1e-6)) }
        val rawG = DoubleArray(n) { if (points[it].kTarget != null) min(points[it].samples, 6) / 6.0 else 0.0 }
        val g = DoubleArray(n) { i -> min(1.0, (if (i > 0) 0.25 * rawG[i - 1] else 0.0) + 0.5 * rawG[i] + (if (i < n - 1) 0.25 * rawG[i + 1] else 0.0)) / 0.5 * 0.5 }
        val target = DoubleArray(n) { i -> points[i].kTarget?.let { ln(it) } ?: lnCur[i] }
        val wPrior = DoubleArray(n) { 1.0 * (1 - g[it]) + 0.05 * g[it] }
        var h2 = 0.0; for (i in 0 until n - 1) h2 += (u[i + 1] - u[i]) * (u[i + 1] - u[i]); h2 /= (n - 1)
        var x = lnCur.copyOf()
        repeat(Eq.IRLS_ITER) {
            val res = DoubleArray(n) { target[it] - x[it] }
            val scale = max(1e-6, res.map { abs(it) }.sorted()[n / 2] * 1.4826)
            val rob = DoubleArray(n) { val r = res[it]; if (abs(r) < Eq.TUKEY_C * scale) { val q = 1 - (r / (Eq.TUKEY_C * scale)).let { z -> z * z }; q * q } else 0.0 }
            val a = Array(n) { DoubleArray(n) }; val b = DoubleArray(n)
            for (i in 0 until n) { val wd = g[i] * rob[i]; a[i][i] += wd + wPrior[i]; b[i] += wd * target[i] + wPrior[i] * lnCur[i] }
            for (i in 1 until n - 1) {
                val hl = u[i] - u[i - 1]; val hr = u[i + 1] - u[i]
                val row = linkedMapOf(i - 1 to 2 / (hl * (hl + hr)), i to -2 / (hl * hr), i + 1 to 2 / (hr * (hl + hr)))
                for ((ra, va) in row) for ((rc, vc) in row) a[ra][rc] += Eq.LAMBDA * h2 * h2 * va * vc
            }
            x = solve(a, b)
        }
        val lo = DoubleArray(n) { max(ln(Eq.K_MIN), if (axisMs[it] < Eq.LOW_GUARD_MS) lnCur[it] else -9.0) }
        val hi = ln(Eq.K_MAX)
        for (pass in 0 until 50) {
            var moved = false
            for (i in 0 until n) { val v = min(max(x[i], lo[i]), hi); if (v != x[i]) { x[i] = v; moved = true } }
            for (i in 0 until n - 1) {
                val d = x[i + 1] - x[i]; val lim = min(Eq.MAX_STEP_LN, Eq.MAX_ELASTICITY * (u[i + 1] - u[i]))
                if (abs(d) > lim + 1e-12) { val over = (abs(d) - lim) / 2 * (if (d > 0) 1 else -1); x[i] += over; x[i + 1] -= over; moved = true }
            }
            if (!moved) break
        }
        val kRaw = IntArray(n) { min(0xFFFF, (exp(x[it]) * Eq.Q14).toInt()) }
        val origin = (0 until n).map { i ->
            val d = abs(x[i] - lnCur[i])
            if (g[i] >= 0.5) Origin.MEDIDO else if (g[i] > 0 && d > 0.0025) Origin.MISTO else if (d > 0.0025) Origin.SUAVIZADO else Origin.MANTIDO
        }
        val steps = (2 until 22).map { abs(x[it + 1] - x[it]) }
        val elas = (2 until 22).map { abs(x[it + 1] - x[it]) / (u[it + 1] - u[it]) }
        val second = (3 until 22).map { x[it + 1] - 2 * x[it] + x[it - 1] }
        val rough = sqrt(second.sumOf { it * it } / second.size)
        val signs = second.zipWithNext().count { (p, q) -> p * q < 0 }
        return Proposal(kRaw, origin, steps.max(), elas.max(), rough, signs)
    }

    /** Eliminação de Gauss com pivô parcial, mesma ordem de operações do oráculo. */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { a[it].copyOf(n + 1).also { r -> r[n] = b[it] } }
        for (c in 0 until n) {
            var p = c; for (r in c until n) if (abs(m[r][c]) > abs(m[p][c])) p = r
            val t = m[c]; m[c] = m[p]; m[p] = t
            for (r in c + 1 until n) {
                val f = m[r][c] / m[c][c]
                if (f != 0.0) for (k in c..n) m[r][k] -= f * m[c][k]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) { var s = 0.0; for (k in r + 1 until n) s += m[r][k] * x[k]; x[r] = (m[r][n] - s) / m[r][r] }
        return x
    }
}
