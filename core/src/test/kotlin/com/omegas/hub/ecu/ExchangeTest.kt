package com.omegas.hub.ecu

import com.omegas.hub.support.ScriptedPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Envelope: ACK, erro da ECU (recuperável ou não), e as três falhas de transporte. */
class ExchangeTest {
    private fun exchange(vararg script: Pair<String, List<String>>) = ScriptedPort(script.toMap()).let { it to Exchange(it) }

    @Test fun ackComPayload() {
        val (_, ex) = exchange("09 74 01 7E" to listOf("09 74 01 7E 53 01 03 57"))
        val r = ex.send(Mp48Frames.readScalar(0x0174)) as Reply.Ack
        assertEquals(3, r.payload[0].toInt())
    }

    @Test fun erroDefinitivoNaoRepete() {
        val (port, ex) = exchange("48 08 50" to listOf("48 08 50 CA 01 10 DB"))
        val r = ex.sendWithRetry(byteArrayOf(0x48, 0x08, 0x50)) as Reply.EcuError
        assertEquals(0x10, r.code); assertEquals(1, port.sent.size)
    }

    @Test fun erroRecuperavelRepeteUmaVez() {
        val (port, ex) = exchange("48 01 49" to listOf("48 01 49 CA 01 08 D3", "48 01 49 CA 01 08 D3"))
        assertTrue(ex.sendWithRetry(Mp48Frames.TELEMETRY) is Reply.EcuError)
        assertEquals(2, port.sent.size)
    }

    @Test fun silencioEcoEChecksumSaoTransporte() {
        assertEquals("silêncio", (exchange("48 01 49" to listOf("")).second.send(Mp48Frames.TELEMETRY) as Reply.Transport).reason)
        assertEquals("eco diferente", (exchange("48 01 49" to listOf("48 01 48 53 00 53")).second.send(Mp48Frames.TELEMETRY) as Reply.Transport).reason)
        assertEquals("checksum errado", (exchange("09 74 01 7E" to listOf("09 74 01 7E 53 01 03 58")).second.send(Mp48Frames.readScalar(0x0174)) as Reply.Transport).reason)
        assertEquals("payload incompleto", (exchange("09 74 01 7E" to listOf("09 74 01 7E 53 05 03")).second.send(Mp48Frames.readScalar(0x0174)) as Reply.Transport).reason)
    }
}
