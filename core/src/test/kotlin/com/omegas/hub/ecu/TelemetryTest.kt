package com.omegas.hub.ecu

import com.omegas.hub.support.Corpus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryTest {
    /** Quadro de referência do ProgBase (docs/spec/mp48-frames.md §2). */
    private val reference = Corpus.bytes("6B 03 90 24 00 00 15 11 53 07 00 90 2C 7E 09 07 45 C4 01 E1 00 00 00 00 FA 10 00 00 48 07 00 00 00 00")

    @Test fun quadroDeReferencia() {
        val t = Telemetry.decode(reference, 1L)!!
        assertEquals(875, t.rpm)
        assertEquals(Fuel.GNV, t.fuel)
        assertEquals(4.800, t.petrolMs, 1e-9)
        assertEquals(11.19488, t.gasMs, 1e-9)
        assertEquals(65, t.waterC)
        assertEquals(49, t.gasC)
        assertEquals(126, t.levelRaw)
        assertEquals(2.25125, t.gasBar, 1e-9)
        assertEquals(0.452, t.mapBar, 1e-9)
    }

    @Test fun combustivelPorByte() {
        mapOf(0x80 to Fuel.GASOLINA, 0xA0 to Fuel.GASOLINA, 0x88 to Fuel.TRANSICAO, 0xA8 to Fuel.TRANSICAO,
              0x90 to Fuel.GNV, 0xB0 to Fuel.GNV, 0x00 to Fuel.DESLIGADO, 0xA1 to Fuel.DESCONHECIDO, 0x94 to Fuel.DESCONHECIDO)
            .forEach { (b, f) -> assertEquals("%02X".format(b), f, Telemetry.fuelOf(b)) }
    }

    @Test fun corteFisicoValeMaisQueOByte() {
        val p = reference.copyOf()
        p[0] = 0xB0.toByte(); p[1] = 0x04            // 1200 rpm
        p[6] = 0; p[7] = 0                           // gás 0
        p[8] = 0x80.toByte(); p[9] = 0x00            // gasolina 0,33 ms
        p[17] = 0x2C; p[18] = 0x01                   // MAP 0,30 bar
        assertEquals(Fuel.CUTOFF, Telemetry.decode(p, 0)!!.fuel)
    }

    @Test fun mapNegativoEhSinalizado() {
        val p = reference.copyOf(); p[17] = 0xFF.toByte(); p[18] = 0xFF.toByte()
        assertEquals(-0.001, Telemetry.decode(p, 0)!!.mapBar, 1e-9)
    }

    /** Classe 3: todos os quadros reais do corpus decodificam em valores físicos plausíveis. */
    @Test fun corpusRealPlausivel() {
        val frames = Corpus.all.filter { it.request == "48 01 49" && it.response.length >= 40 * 3 - 1 }
        assertTrue(frames.size > 20_000)
        var gnv = 0
        for (f in frames) {
            val t = Telemetry.decode(Corpus.bytes(f.response).copyOfRange(5, 39), 0)
            assertNotNull(t)
            assertTrue("rpm ${t!!.rpm}", t.rpm in 0..8000)
            assertTrue("map ${t.mapBar}", t.mapBar in -0.2..2.5)
            assertTrue("gasolina ${t.petrolMs}", t.petrolMs in 0.0..40.0)
            if (t.fuel == Fuel.GNV) gnv++
        }
        assertTrue("o log tem trechos em GNV", gnv > 1000)
    }
}
