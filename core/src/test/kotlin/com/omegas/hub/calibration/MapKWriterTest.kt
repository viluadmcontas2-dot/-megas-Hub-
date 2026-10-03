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

class MapKWriterTest {
    /** Classe 3: o ProgBase gravou o mapa inteiro (144 células) em 100; o Hub reproduz os mesmos bytes, na mesma ordem. */
    @Test fun gravacaoCompletaReproduzOProgBaseByteAByte() {
        val all = Corpus.all
        val on = all.indexOfFirst { it.request == "35 03 00 86 2C 51 10 4B" }
        val off = all.withIndex().first { it.index > on && it.value.request == "35 03 00 86 24 51 10 43" }.index
        val progBase = all.subList(on, off + 1).map { it.request }.filter { it.startsWith("35") || it.startsWith("14 54") }

        // Respostas: as da ECU real; a linha relida depois da gravação vem toda em 100 (o que a ECU real passou a ter).
        val port = ReplayPort { req ->
            val hex = Mp48Frames.hex(req)
            if (hex.startsWith("2A 54 00")) Corpus.bytes(ackFor(req, ByteArray(12) { 100 }))
            else Corpus.responseTo(req)?.let(Corpus::bytes)
        }
        val writer = MapKWriter(Exchange(port))
        val cells = (0 until 12).flatMap { r -> (0 until 12).map { c -> Cell(r, c, 100) } }
        val outcomes = cells.chunked(16).map { writer.write(it).outcome }
        assertTrue(outcomes.toString(), outcomes.all { it is Outcome.Done })

        val hub = port.sentHex.filter { it.startsWith("35") || it.startsWith("14 54") }
        // 9 lotes de 16 → 9 pares on/off; o ProgBase fez um par só. Comparar o conteúdo sem os pares.
        assertEquals(progBase.filter { it.startsWith("14 54") }, hub.filter { it.startsWith("14 54") })
        assertEquals(9, hub.count { it == "35 03 00 86 2C 51 10 4B" })
        assertEquals(9, hub.count { it == "35 03 00 86 24 51 10 43" })
        // Ordem dentro de um lote: on, escritas, off; readback só depois do off.
        val first = port.sentHex.dropWhile { !it.startsWith("35") }
        assertTrue(first[0].startsWith("35 03 00 86 2C")); assertTrue(first[17].startsWith("35 03 00 86 24")); assertTrue(first[18].startsWith("2A 54"))
    }

    @Test fun leituraRealDoMapa() {
        val map = MapKWriter(Exchange(ReplayPort())).read()
        assertEquals(12, map.rows.size); assertEquals(162, map.rows[0][0]); assertEquals(12, map.technicalRow.size)
    }

    @Test fun readbackDivergenteNaoEhGravado() {
        val row0 = Mp48Frames.readMapRow(0)
        val port = ScriptedPort(mapOf(
            Mp48Frames.hex(row0) to listOf(ackFor(row0, ByteArray(12) { 100 }), ackFor(row0, ByteArray(12) { 100 })),  // antes e depois iguais: célula não mudou
            "35 03 00 86 2C 51 10 4B" to listOf("35 03 00 86 2C 51 10 4B 53 00 53"),
            "14 54 00 00 03 6E D9" to listOf("14 54 00 00 03 6E D9 53 00 53"),
            "35 03 00 86 24 51 10 43" to listOf("35 03 00 86 24 51 10 43 53 00 53")))
        val w = MapKWriter(Exchange(port)).write(listOf(Cell(0, 3, 110)))
        val f = w.outcome as Outcome.Failed
        assertEquals(FailureKind.ECU, f.kind); assertTrue(f.text, f.text.contains("[0,3]"))
        assertEquals(listOf(100), w.photo!!.getValue(0).toList().distinct())
        assertEquals("modo desligado sempre vem depois das escritas", "35 03 00 86 24 51 10 43", port.sent[3])
    }

    @Test fun ackAusenteAbortaEDesligaOModo() {
        val row0 = Mp48Frames.readMapRow(0)
        val port = ScriptedPort(mapOf(
            Mp48Frames.hex(row0) to listOf(ackFor(row0, ByteArray(12) { 100 })),
            "35 03 00 86 2C 51 10 4B" to listOf("35 03 00 86 2C 51 10 4B 53 00 53"),
            "14 54 00 00 03 6E D9" to listOf("14 54 00 00 03 6E D9 CA 01 10 DB"),
            "35 03 00 86 24 51 10 43" to listOf("35 03 00 86 24 51 10 43 53 00 53")))
        val f = MapKWriter(Exchange(port)).write(listOf(Cell(0, 3, 110), Cell(0, 4, 110))).outcome as Outcome.Failed
        assertEquals(FailureKind.ECU, f.kind)
        assertEquals(listOf("2A 54 00 00 7E", "35 03 00 86 2C 51 10 4B", "14 54 00 00 03 6E D9", "35 03 00 86 24 51 10 43"), port.sent)
    }

    @Test fun kAbaixoDe100EhRecusadoAntesDeTocarAEcu() {
        val port = ScriptedPort(emptyMap())
        val f = MapKWriter(Exchange(port)).write(listOf(Cell(0, 0, 99))).outcome as Outcome.Failed
        assertEquals(FailureKind.APP, f.kind); assertTrue(port.sent.isEmpty())
    }
}
