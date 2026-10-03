package com.omegas.hub.equivalence

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Curva própria: ln T por célula (null onde não há dado nem prior), peso próprio e dispersão. */
class FittedCurve(val lnT: Array<Double?>, val ownWeight: DoubleArray, val dispersion: DoubleArray) {
    /** Pares (MAP do centro da célula, ln T) só das células definidas. */
    val xs: List<Double> = lnT.indices.filter { lnT[it] != null }.map { Eq.cellCenter(it) }
    val ys: List<Double> = lnT.filterNotNull()
    fun at(mapBar: Double): Double? = if (xs.isEmpty()) null else Fit.interp(xs, ys, mapBar, true)

    /** MAP onde a curva (crescente) vale lnT; null fora do domínio medido. */
    fun inverse(ln: Double): Double? {
        if (xs.size < 2 || ln < ys.first() || ln > ys.last()) return null
        for (j in 1 until xs.size) if (ln <= ys[j]) {
            val (x0, y0, x1, y1) = listOf(xs[j - 1], ys[j - 1], xs[j], ys[j])
            return if (y1 == y0) x0 else x0 + (ln - y0) / (y1 - y0) * (x1 - x0)
        }
        return xs.last()
    }
}

object Fit {
    /** Interpolação linear; nas pontas, constante (extrapolate) ou null. xs crescente. */
    fun interp(xs: List<Double>, ys: List<Double>, x: Double, extrapolate: Boolean): Double? {
        if (xs.isEmpty()) return null
        if ((x < xs.first() - 1e-12 || x > xs.last() + 1e-12) && !extrapolate) return null
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        for (j in 1 until xs.size) if (x <= xs[j]) {
            val f = (x - xs[j - 1]) / (xs[j] - xs[j - 1]); return ys[j - 1] + f * (ys[j] - ys[j - 1])
        }
        return ys.last()
    }

    /** Regressão isotônica crescente ponderada (pool-adjacent-violators). */
    fun pava(values: List<Double>, weights: List<Double>): List<Double> {
        val out = ArrayList<DoubleArray>() // [média, peso, tamanho]
        for (k in values.indices) {
            out.add(doubleArrayOf(values[k], weights[k], 1.0))
            while (out.size > 1 && out[out.size - 2][0] > out[out.size - 1][0]) {
                val a = out.removeAt(out.size - 1); val c = out.removeAt(out.size - 1)
                val w = a[1] + c[1]
                out.add(doubleArrayOf(if (w > 0) (a[0] * a[1] + c[0] * c[1]) / w else (a[0] + c[0]) / 2, w, a[2] + c[2]))
            }
        }
        val res = ArrayList<Double>()
        for (b in out) repeat(b[2].toInt()) { res.add(b[0]) }
        return res
    }

    /** Mistura própria+prior por célula, isotônica em MAP, outliers rejeitados (oracle.fit_curve). */
    fun curve(cells: Cells, priorMap: List<Double>, priorLn: List<Double>): FittedCurve {
        val xs = ArrayList<Int>(); val raw = ArrayList<Double>(); val w = ArrayList<Double>()
        val ownW = DoubleArray(Eq.CELLS); val disp = DoubleArray(Eq.CELLS)
        for (i in 0 until Eq.CELLS) {
            val n = cells.count(i)
            val p = if (priorMap.isNotEmpty()) interp(priorMap, priorLn, Eq.cellCenter(i), false) else null
            if (n == 0 && p == null) continue
            val d = if (n > 0) cells.dispersion(i) else 0.0
            var g = if (n > 0) min(n / Eq.BAND_MATURE_COUNT.toDouble(), 1.0) * (1 - min(d / 0.08, 1.0) * 0.5) else 0.0
            if (p == null) g = 1.0
            ownW[i] = g; disp[i] = d
            val v = (if (n > 0) cells.mean(i) else 0.0) * g + (p ?: 0.0) * (1 - g)
            xs.add(i); raw.add(v); w.add(0.05 + g)
        }
        if (xs.isEmpty()) return FittedCurve(Array(Eq.CELLS) { null }, ownW, disp)
        val keep = BooleanArray(xs.size) { true }
        for (pass in 0 until Eq.OUTLIER_PASSES) {
            val idx = xs.indices.filter { keep[it] }
            val fitted = pava(idx.map { raw[it] }, idx.map { w[it] })
            val res = idx.indices.map { raw[idx[it]] - fitted[it] }
            if (res.isEmpty()) break
            val med = res.map { abs(it) }.sorted()[res.size / 2]
            val thr = max(Eq.OUTLIER_MIN_LOG, Eq.MAD_K * med)
            var changed = false
            for (k in idx.indices) if (abs(res[k]) > thr && ownW[xs[idx[k]]] < 1.0) { keep[idx[k]] = false; changed = true }
            if (!changed) break
        }
        val keptIdx = xs.indices.filter { keep[it] }
        val keptX = keptIdx.map { xs[it] }
        val fitted = pava(keptIdx.map { raw[it] }, keptIdx.map { w[it] })
        val centers = keptX.map { Eq.cellCenter(it) }
        val out = Array<Double?>(Eq.CELLS) { null }
        for (i in 0 until Eq.CELLS) if (keptX.isNotEmpty() && keptX.first() <= i && i <= keptX.last()) out[i] = interp(centers, fitted, Eq.cellCenter(i), true)
        return FittedCurve(out, ownW, disp)
    }
}
