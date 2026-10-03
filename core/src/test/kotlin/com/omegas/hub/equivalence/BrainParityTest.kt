package com.omegas.hub.equivalence

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Paridade Python ↔ Kotlin (classe 3): o cérebro Kotlin reproduz, nas mesmas sessões reais e nos mesmos
 * quadros, os pontos, o índice, a próxima ação e a proposta que o oráculo gerou em fixtures/equivalence/parity.json.
 */
class BrainParityTest {
    private val parity = JSONObject(File("../fixtures/equivalence/parity.json").readText())
    private val eps = 1e-9

    @Test fun kotlinReproduzOOraculoNasSessoesReais() {
        val sessions = parity.getJSONArray("sessions")
        var checked = 0
        for (s in 0 until sessions.length()) {
            val session = sessions.getJSONObject(s)
            val data = JSONObject(GZIPInputStream(File("../" + session.getString("file")).inputStream()).bufferedReader().readText())
            val checks = session.getJSONArray("checks")
            val expectedByFrame = (0 until checks.length()).map { checks.getJSONObject(it) }.associateBy { it.getInt("frame") }
            val brain = Brain()
            val snaps = data.getJSONArray("snapshots").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.sortedBy { it.getInt("sequence") }
            var si = 0
            val tel = data.getJSONArray("telemetry")
            for (n in 0 until tel.length()) {
                while (si < snaps.size && snaps[si].getInt("sequence") <= n) {
                    val f = fields(snaps[si])
                    brain.feedNative(axis(f), f.getValue("MUL_ACT"), bands(f, "NUM_BUF_UPD_PETR", "PETR_INJ_TBUF", "MNFLD_PRESS_BUF"), bands(f, "NUM_BUF_UPD_GAS", "PETR_INJ_TBUF_GAS", "MNFLD_PRESS_BUF_GAS"))
                    if (snaps[si].getString("snapshotReason") == "ACTION_RESET_GAS") brain.resetGasLane()
                    if (brain.reference == null) brain.freeze(0L)
                    si++
                }
                val fr = tel.getJSONObject(n)
                brain.feed(Frame(fr.getDouble("rpm"), fr.getDouble("load_bar"), fr.optDouble("petrol_ms", 0.0).let { if (it.isNaN()) 0.0 else it },
                    fr.optDouble("gas_ms_diagnostic", 0.0).let { if (it.isNaN()) 0.0 else it }, fr.getString("fuel"), fr.getLong("t")))
                val exp = expectedByFrame[n] ?: continue
                val view = brain.view()
                assertEquals("gasolina $n", exp.getInt("petrolSamples"), brain.petrol.total())
                assertEquals("gás $n", exp.getInt("gasSamples"), brain.gas.total())
                assertEquals("ação $n", exp.getString("nextAction"), view.nextAction.text)
                val idx = exp.optJSONObject("index")
                if (idx == null) assertNull(view.index) else {
                    if (idx.isNull("value")) assertNull(view.index!!.value) else assertEquals(idx.getDouble("value"), view.index!!.value!!, eps)
                    assertEquals(idx.getDouble("coverage"), view.index!!.coverage, eps)
                }
                val pts = exp.getJSONArray("points")
                for (i in 0 until pts.length()) {
                    val p = pts.getJSONObject(i); val k = view.points[i]
                    assertEquals("estado $n/$i", p.getString("state"), k.state.name)
                    assertEquals("n $n/$i", p.getInt("samples"), k.samples)
                    assertEquals("uso $n/$i", p.getDouble("usage"), k.usage, eps)
                    assertEquals("tol $n/$i", p.getDouble("tolerance"), k.tolerance, eps)
                    optEq("kTarget $n/$i", p, "kTarget", k.kTarget); optEq("mix $n/$i", p, "mixture", k.mixture); optEq("map $n/$i", p, "mapBar", k.mapBar)
                }
                val prop = exp.optJSONObject("proposal")
                val got = brain.propose(view)
                if (prop == null) assertNull(got) else {
                    val kr = prop.getJSONArray("kRaw")
                    assertEquals("proposta $n", (0 until kr.length()).map { kr.getInt(it) }, got!!.kRaw.toList())
                    val org = prop.getJSONArray("origin")
                    assertEquals((0 until org.length()).map { org.getString(it) }, got.origin.map { it.name })
                    assertEquals(prop.getDouble("maxNeighborStep"), got.maxNeighborStep, 1e-9)
                    assertEquals(prop.getDouble("maxElasticity"), got.maxElasticity, 1e-9)
                    assertEquals(prop.getDouble("roughness"), got.roughness, 1e-9)
                    assertEquals(prop.getInt("signChanges"), got.signChanges)
                }
                checked++
            }
        }
        assertTrue(checked >= 12)
    }

    /** Portão do spec §11: na sessão com AutoMatch nativo, o índice no fim é maior que logo após a troca de época. */
    @Test fun indiceSobeDepoisDoAjuste() {
        val s = parity.getJSONArray("sessions").getJSONObject(1).getJSONArray("checks")
        val after = s.getJSONObject(2).getJSONObject("index").getDouble("value")   // quadro 1999, logo após o AutoMatch
        val end = s.getJSONObject(3).getJSONObject("index").getDouble("value")
        assertTrue("$after → $end", end > after)
    }

    private fun optEq(msg: String, p: JSONObject, key: String, v: Double?) { if (p.isNull(key)) assertNull(msg, v) else assertEquals(msg, p.getDouble(key), v!!, eps) }
    private fun fields(snap: JSONObject): Map<String, IntArray> {
        val arr = snap.getJSONArray("fields")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.associate { f -> f.getString("key") to f.getJSONArray("rawValues").let { r -> IntArray(r.length()) { r.getInt(it) } } }
    }
    private fun axis(f: Map<String, IntArray>) = f.getValue("PETR_INJ_TBP").map { it / 512.0 }.toDoubleArray()
    private fun s16(v: Int) = if (v > 32767) v - 65536 else v
    private fun bands(f: Map<String, IntArray>, n: String, inj: String, map: String) = (0 until 18).map { Band(f.getValue(n)[it], f.getValue(inj)[it] / 512.0, s16(f.getValue(map)[it]) / 1024.0) }
}
