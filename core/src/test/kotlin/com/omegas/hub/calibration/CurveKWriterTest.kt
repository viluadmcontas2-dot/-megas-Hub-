package com.omegas.hub.calibration

import com.omegas.hub.ecu.Exchange
import com.omegas.hub.ecu.Mp48Frames
import com.omegas.hub.support.Corpus
import com.omegas.hub.support.ReplayPort
import com.omegas.hub.support.ScriptedPort
import com.omegas.hub.support.ackFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveKWriterTest {
    private val readAxis = Mp48Frames.readVector(CurveK.ADDR_AXIS)
    private val readFactors = Mp48Frames.readVector(CurveK.ADDR_FACTORS)
    private val axisHex = Corpus.responseTo(readAxis)!!
    private val neutral = IntArray(30) { CurveK.NEUTRAL }

    private fun factorsAck(raw: IntArray) = ackFor(readFactors, raw.toLeBytes())

    @Test fun leituraRealDaCurva() {
        val c = CurveKWriter(Exchange(ReplayPort())).read()
        assertEquals(listOf(0.5, 1.0, 1.5), c.axisMs.take(3)); assertEquals(22.0, c.axisMs.last(), 0.0)
        assertEquals(0x35B1, c.factorsRaw[0]); assertEquals(0.8389, c.factors[0], 0.0001)
    }

    @Test fun conversaoQ14TruncaEProtegeMinimo() {
        assertEquals(0x34DD, CurveK.rawOf(0x34DD / CurveK.Q14))
        assertEquals(0x4000, CurveK.rawOf(1.0))
        assertEquals(13107, CurveK.rawOf(0.8))           // 0,8 × 16384 = 13107,2 → trunca
        assertEquals(0xFFFF, CurveK.rawOf(5.0))
        try { CurveK.rawOf(0.59); error("devia recusar") } catch (_: IllegalArgumentException) {}
    }

    @Test fun gravaUmPontoComFotoEReadback() {
        val after = neutral.copyOf().also { it[9] = 0x34DD }
        val port = ScriptedPort(mapOf(
            Mp48Frames.hex(readAxis) to listOf(axisHex),
            Mp48Frames.hex(readFactors) to listOf(factorsAck(neutral), factorsAck(after)),
            "14 61 01 09 DD 34 90" to listOf("14 61 01 09 DD 34 90 53 00 53")))
        val w = CurveKWriter(Exchange(port)).write(mapOf(9 to 0x34DD))
        assertTrue(w.outcome.toString(), w.outcome is Outcome.Done)
        assertEquals(neutral.toList(), w.photo!!.factorsRaw.toList())
        assertEquals(listOf("29 4B 01 75", "29 61 01 8B", "14 61 01 09 DD 34 90", "29 61 01 8B"), port.sent)
    }

    @Test fun readbackDivergenteEhFalhaDaEcu() {
        val port = ScriptedPort(mapOf(
            Mp48Frames.hex(readAxis) to listOf(axisHex),
            Mp48Frames.hex(readFactors) to listOf(factorsAck(neutral), factorsAck(neutral)),
            "14 61 01 09 DD 34 90" to listOf("14 61 01 09 DD 34 90 53 00 53")))
        val f = CurveKWriter(Exchange(port)).write(mapOf(9 to 0x34DD)).outcome as Outcome.Failed
        assertEquals(FailureKind.ECU, f.kind); assertTrue(f.text, f.text.contains("ponto 9"))
    }

    @Test fun ecuRecusaEhFalhaDaEcuETransporteEhTransporte() {
        val base = mapOf(Mp48Frames.hex(readAxis) to listOf(axisHex), Mp48Frames.hex(readFactors) to listOf(factorsAck(neutral)))
        val refused = CurveKWriter(Exchange(ScriptedPort(base + ("14 61 01 09 DD 34 90" to listOf("14 61 01 09 DD 34 90 CA 01 10 DB"))))).write(mapOf(9 to 0x34DD)).outcome as Outcome.Failed
        assertEquals(FailureKind.ECU, refused.kind)
        val silent = CurveKWriter(Exchange(ScriptedPort(base + ("14 61 01 09 DD 34 90" to listOf(""))))).write(mapOf(9 to 0x34DD)).outcome as Outcome.Failed
        assertEquals(FailureKind.TRANSPORTE, silent.kind)
    }

    @Test fun resetEnviaOs30PontosEmOrdem() {
        val current = IntArray(30) { 0x3800 + it }
        val script = HashMap<String, List<String>>()
        script[Mp48Frames.hex(readAxis)] = listOf(axisHex)
        script[Mp48Frames.hex(readFactors)] = listOf(factorsAck(current), factorsAck(neutral))
        for (i in 0 until 30) { val r = Mp48Frames.writeU16Indexed(CurveK.ADDR_FACTORS, i, CurveK.NEUTRAL); script[Mp48Frames.hex(r)] = listOf(Mp48Frames.hex(r) + " 53 00 53") }
        val port = ScriptedPort(script)
        assertTrue(CurveKWriter(Exchange(port)).reset().outcome is Outcome.Done)
        val writes = port.sent.filter { it.startsWith("14 61") }
        assertEquals(30, writes.size); assertEquals("14 61 01 00 00 40 B6", writes.first()); assertEquals("14 61 01 1D 00 40 D3", writes.last())
    }

    @Test fun restoreRecusaEixoDiferente() {
        val port = ScriptedPort(mapOf(Mp48Frames.hex(readAxis) to listOf(axisHex), Mp48Frames.hex(readFactors) to listOf(factorsAck(neutral))))
        val other = CurveK(IntArray(30) { 0x100 * (it + 2) }, neutral)
        val f = CurveKWriter(Exchange(port)).restore(other).outcome as Outcome.Failed
        assertEquals(FailureKind.APP, f.kind); assertEquals(2, port.sent.size)
    }
}
