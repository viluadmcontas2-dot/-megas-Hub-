package com.omegas.hub.equivalence

import kotlin.math.ln

/** Uma banda nativa do AutoCal em unidades físicas. */
data class Band(val n: Int, val injMs: Double, val mapBar: Double)

/** Referência congelada (docs/spec/equivalence.md §3): as bandas de gasolina da ECU no momento do toque. */
data class Reference(val frozenAtMs: Long, val bands: List<Band>)

/** O que o cérebro entrega por revisão. */
data class BrainView(val index: Index?, val points: List<EquivalencePoint>, val nextAction: NextAction, val petrol: FittedCurve?, val gas: FittedCurve?)

/**
 * O cérebro: consome quadros e snapshots nativos, mantém amostras próprias e devolve os 30 pontos,
 * o índice e a próxima ação. Nunca fala com a ECU: só o dono grava (via proposta).
 */
class Brain {
    private val stabilizer = Stabilizer()
    var petrol = Cells(); private set
    var gas = Cells(); private set
    var axisMs: DoubleArray? = null; private set
    var kRaw: IntArray? = null; private set
    var reference: Reference? = null; private set
    private var nativePetrol: List<Band> = emptyList()
    private var nativeGas: List<Band> = emptyList()
    private val written = LinkedHashMap<Int, Double>()

    fun feed(f: Frame): Sample? {
        val s = stabilizer.feed(f) ?: return null
        (if (s.fuel == "GASOLINA") petrol else gas).add(s)
        return s
    }

    /** Snapshot nativo coerente. Curva K diferente da anterior = a pista de gás recomeça. */
    fun feedNative(axisMs: DoubleArray, kRaw: IntArray, petrolBands: List<Band>, gasBands: List<Band>) {
        val old = this.kRaw
        if (old != null && !old.contentEquals(kRaw)) resetGasLane()
        this.axisMs = axisMs; this.kRaw = kRaw; nativePetrol = petrolBands; nativeGas = gasBands
    }

    fun resetGasLane() { gas = Cells() }

    fun freeze(atMs: Long): Boolean {
        if (nativePetrol.isEmpty()) return false
        reference = Reference(atMs, nativePetrol); return true
    }

    /** Depois de uma gravação do dono: guarda a mistura de cada ponto gravado e recomeça o gás. */
    fun onCurveWritten(indices: Collection<Int>, points: List<EquivalencePoint>) {
        for (i in indices) written[i] = points[i].mixture ?: 0.0
        resetGasLane()
    }

    private fun prior(bands: List<Band>): Pair<List<Double>, List<Double>> {
        val pts = bands.filter { it.n >= Eq.PRIOR_MIN_N && it.injMs > 0 }.map { it.mapBar to ln(it.injMs) }.sortedWith(compareBy({ it.first }, { it.second }))
        return pts.map { it.first } to pts.map { it.second }
    }

    fun view(operating: Boolean = false): BrainView {
        val axis = axisMs ?: return BrainView(null, emptyList(), NextAction("AGUARDE", "Lendo a ECU…"), null, null)
        val k = kRaw!!
        val ref = reference
        val (pm, pl) = if (ref != null) prior(ref.bands) else emptyList<Double>() to emptyList()
        val (gm, gl) = prior(nativeGas)
        val petrolCurve = Fit.curve(petrol, pm, pl)
        val gasCurve = Fit.curve(gas, gm, gl)
        val points = if (ref != null) Points.compute(axis, k, gasCurve, petrolCurve, gas, written)
            else axis.indices.map { EquivalencePoint(it, axis[it], k[it] / Eq.Q14, null, null, Eq.TOL_FLOOR, null, 0, 0.0, PointState.SEM_DADOS) }
        val mature = nativePetrol.count { it.n >= 3 }
        return BrainView(Points.index(points, ref != null), points, Points.nextAction(points, ref != null, mature, operating), petrolCurve, gasCurve)
    }

    fun propose(view: BrainView): Proposal? {
        val axis = axisMs ?: return null
        if (view.points.none { it.kTarget != null }) return null
        return Proposer.propose(axis, DoubleArray(axis.size) { kRaw!![it] / Eq.Q14 }, view.points)
    }
}
