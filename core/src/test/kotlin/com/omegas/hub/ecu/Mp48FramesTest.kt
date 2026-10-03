package com.omegas.hub.ecu

import com.omegas.hub.autocal.AutoCalAction
import com.omegas.hub.autocal.Field
import com.omegas.hub.calibration.CurveK
import com.omegas.hub.support.Corpus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Classe 1 (bytes canônicos do spec) e 3 (cada pedido que o Hub pode enviar existe no corpus real). */
class Mp48FramesTest {
    private fun hex(b: ByteArray) = Mp48Frames.hex(b)

    @Test fun bytesCanonicosDoSpec() {
        assertEquals("00 02 02", hex(Mp48Frames.INIT_1))
        assertEquals("01 00 3A 3B", hex(Mp48Frames.INIT_2))
        assertEquals("00 25 25", hex(Mp48Frames.IDENTIFY))
        assertEquals("00 01 01", hex(Mp48Frames.CLOSE))
        assertEquals("48 01 49", hex(Mp48Frames.TELEMETRY))
        assertEquals("48 0B 53", hex(Mp48Frames.NATIVE_STATUS))
        assertEquals("29 61 01 8B", hex(Mp48Frames.readVector(CurveK.ADDR_FACTORS)))
        assertEquals("29 4B 01 75", hex(Mp48Frames.readVector(CurveK.ADDR_AXIS)))
        assertEquals("09 74 01 7E", hex(Field.AUTOMATCH_COUNT.request))
        assertEquals("14 61 01 09 DD 34 90", hex(Mp48Frames.writeU16Indexed(CurveK.ADDR_FACTORS, 9, 0x34DD)))
        assertEquals("14 61 01 00 00 40 B6", hex(Mp48Frames.writeU16Indexed(CurveK.ADDR_FACTORS, 0, CurveK.NEUTRAL)))
        assertEquals("14 61 01 1D 00 40 D3", hex(Mp48Frames.writeU16Indexed(CurveK.ADDR_FACTORS, 29, CurveK.NEUTRAL)))
        assertEquals("2A 54 00 0C 8A", hex(Mp48Frames.readMapRow(0x0C)))
        assertEquals("14 54 00 00 00 64 CC", hex(Mp48Frames.writeMapCell(0, 0, 100)))
        assertEquals("35 03 00 86 2C 51 10 4B", hex(Mp48Frames.insertionMode(true)))
        assertEquals("35 03 00 86 24 51 10 43", hex(Mp48Frames.insertionMode(false)))
        assertEquals("12 4A 01 01 5E", hex(Mp48Frames.writeU8(Field.ENABLE.addr, 1)))
        assertEquals("12 4A 01 00 5D", hex(Mp48Frames.writeU8(Field.ENABLE.addr, 0)))
        assertEquals("02 24 04 01 2B", hex(AutoCalAction.RESET_PETROL.request))
        assertEquals("02 24 04 02 2C", hex(AutoCalAction.RESET_GAS.request))
    }

    @Test fun todaLeituraDoHubExisteNoCorpusReal() {
        val requests = (Field.ACQUISITION + Field.REFERENCE + Field.AUTOMATCH_COUNT).map { it.request } +
            listOf(Mp48Frames.TELEMETRY, Mp48Frames.NATIVE_STATUS, Mp48Frames.INIT_1, Mp48Frames.INIT_2, Mp48Frames.IDENTIFY, Mp48Frames.CLOSE,
                   Mp48Frames.insertionMode(true), Mp48Frames.insertionMode(false), Mp48Frames.writeU8(Field.ENABLE.addr, 1), Mp48Frames.writeU8(Field.ENABLE.addr, 0)) +
            (0..0x0C).map { Mp48Frames.readMapRow(it) }
        val missing = requests.map(::hex).filter { Corpus.responseTo(Corpus.bytes(it)) == null }
        assertTrue("sem evidência no corpus: $missing", missing.isEmpty())
    }
}
