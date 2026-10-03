package com.omegas.hub.autocal

import com.omegas.hub.calibration.FailureKind
import com.omegas.hub.calibration.Outcome
import com.omegas.hub.ecu.Exchange
import com.omegas.hub.ecu.Mp48Frames
import com.omegas.hub.support.ReplayPort
import com.omegas.hub.support.ScriptedPort
import com.omegas.hub.support.ackFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCalMonitorTest {
    private val sleeps = mutableListOf<Long>()
    private fun monitor(port: com.omegas.hub.usb.SerialPort) = AutoCalMonitor(Exchange(port), clock = { 1000L }, sleep = { sleeps += it })

    /** Classe 3: snapshot com a ECU real; valores físicos do spec autocal.md. */
    @Test fun snapshotRealDecodificaOsCamposDoSpec() {
        val s = monitor(ReplayPort()).snapshot(Field.ACQUISITION + Field.REFERENCE)!!
        assertEquals(Epoch(automatchCount = 3, flag13 = 1), s.epoch)
        assertTrue(!s.partial)
        val thd = s.values.getValue(Field.MAP_THRESHOLDS).map(Field::bar)
        assertEquals(18, thd.size); assertEquals(0.15, thd.first(), 1e-3); assertEquals(1.10, thd.last(), 1e-3)
        val axis = s.values.getValue(Field.AXIS_MS).map(Field::ms)
        assertEquals(30, axis.size); assertEquals(0.5, axis[0], 0.0); assertEquals(22.0, axis[29], 0.0)
        assertEquals(listOf(1, 3, 3, 1, 3, 3, 1, 3, 3, 1), s.values.getValue(Field.CALIBRATION).toList())
        assertEquals(10, s.values.getValue(Field.COUNT_PETROL)[0])
        assertEquals(listOf(1, 1, 1, 1), s.values.getValue(Field.ZONES_PETROL).toList())
        assertEquals(0.8389, Field.factor(s.values.getValue(Field.FACTORS)[0]), 1e-4)
        assertEquals(1, s.values.getValue(Field.ENABLE)[0])
        // MAP em gasolina por banda: inteiros com sinal, crescentes nas bandas com dados
        val mapPetrol = s.values.getValue(Field.MAP_PETROL).map(Field::bar)
        assertTrue(mapPetrol[0] < mapPetrol[5])
    }

    @Test fun epocaMudouDescartaOSnapshot() {
        var countReads = 0
        val port = ReplayPort { req ->
            val hex = Mp48Frames.hex(req)
            if (hex == "09 74 01 7E") Corpus(++countReads) else com.omegas.hub.support.Corpus.responseTo(req)?.let(com.omegas.hub.support.Corpus::bytes)
        }
        assertNull(monitor(port).snapshot(listOf(Field.COUNT_GAS)))
    }
    private fun Corpus(n: Int) = com.omegas.hub.support.Corpus.bytes(if (n == 1) "09 74 01 7E 53 01 03 57" else "09 74 01 7E 53 01 04 58")

    @Test fun resetGasComTestemunhaZerada() {
        val port = ScriptedPort(mapOf(
            "02 24 04 02 2C" to listOf("02 24 04 02 2C 53 00 53"),
            Mp48Frames.hex(Field.COUNT_GAS.request) to listOf(ackFor(Field.COUNT_GAS.request, ByteArray(18))),
            Mp48Frames.hex(Field.ZONES_GAS.request) to listOf(ackFor(Field.ZONES_GAS.request, ByteArray(4)))))
        val o = monitor(port).act(AutoCalAction.RESET_GAS)
        assertTrue(o.toString(), o is Outcome.Done)
        assertEquals(listOf(AutoCalMonitor.SETTLE_MS), sleeps)
        assertEquals(listOf("02 24 04 02 2C", "29 5C 01 86", "29 70 01 9A"), port.sent)
    }

    @Test fun resetSemTestemunhaNaoEhFeito() {
        val port = ScriptedPort(mapOf(
            "02 24 04 01 2B" to listOf("02 24 04 01 2B 53 00 53"),
            Mp48Frames.hex(Field.COUNT_PETROL.request) to listOf(ackFor(Field.COUNT_PETROL.request, ByteArray(18) { if (it < 3) 10 else 0 })),
            Mp48Frames.hex(Field.ZONES_PETROL.request) to listOf(ackFor(Field.ZONES_PETROL.request, ByteArray(4)))))
        val f = monitor(port).act(AutoCalAction.RESET_PETROL) as Outcome.Failed
        assertEquals(FailureKind.ECU, f.kind); assertTrue(f.text, f.text.contains("3 bandas"))
    }

    @Test fun pausarERetomarComTestemunha() {
        val port = ScriptedPort(mapOf(
            "12 4A 01 00 5D" to listOf("12 4A 01 00 5D 53 00 53"),
            Mp48Frames.hex(Field.ENABLE.request) to listOf(ackFor(Field.ENABLE.request, byteArrayOf(0)))))
        assertTrue(monitor(port).setEnabled(false) is Outcome.Done)
        assertNotNull(port.sent.firstOrNull { it == "12 4A 01 00 5D" })
    }
}
