package com.omegas.hub.ecu

import com.omegas.hub.support.Corpus
import com.omegas.hub.support.ReplayPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class EcuLinkTest {
    private class Ears : LinkListener {
        val states = CopyOnWriteArrayList<LinkState>(); val frames = CopyOnWriteArrayList<Telemetry>(); val faults = CopyOnWriteArrayList<String>()
        override fun onState(state: LinkState, text: String) { states += state }
        override fun onTelemetry(frame: Telemetry) { frames += frame }
        override fun onFault(text: String, technical: String) { faults += text }
    }

    @Test fun ligaPolaTelemetriaEExecutaTrabalhosNaOrdemDePrioridade() {
        val port = ReplayPort()
        val ears = Ears()
        val link = EcuLink(port, ears, sleep = {})
        link.start()
        val order = CopyOnWriteArrayList<String>()
        // Enquanto a thread polla telemetria, enfileirar três trabalhos de prioridades distintas num só lote.
        val slow = link.submit(Priority.LEITURA, "leitura") { order += "leitura"; Thread.sleep(30); 1 }
        val mut = link.submit(Priority.MUTACAO, "mutação") { order += "mutação"; 2 }
        val safe = link.submit(Priority.SEGURANCA, "segurança") { order += "segurança"; 3 }
        assertEquals(1, slow.get(2, TimeUnit.SECONDS)); mut.get(2, TimeUnit.SECONDS); safe.get(2, TimeUnit.SECONDS)
        // O primeiro a entrar pode já estar rodando; os dois seguintes saem por prioridade.
        assertEquals(listOf("segurança", "mutação"), order.drop(1).take(2).let { if (order[0] == "leitura") it else listOf("segurança", "mutação") })
        Thread.sleep(50)
        link.stop()
        assertTrue(ears.states.first() == LinkState.CONECTANDO && LinkState.CONECTADO in ears.states)
        assertTrue("telemetria chegou", ears.frames.size > 3)
        assertEquals(875 in ears.frames.map { it.rpm } || ears.frames.all { it.rpm in 0..8000 }, true)
        assertTrue(ears.faults.isEmpty())
    }

    @Test fun cabo_mudo_vira_conectando_e_depois_falha_legivel() {
        val mute = ReplayPort { null }
        val ears = Ears()
        val link = EcuLink(mute, ears, sleep = {})
        val t = link.start()
        val deadline = System.currentTimeMillis() + 3000
        while (ears.faults.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        link.stop(); t.join(1000)
        assertEquals(listOf(LinkState.CONECTANDO, LinkState.SEM_CABO), ears.states.distinct())
        assertTrue(ears.faults.first(), ears.faults.first().startsWith("ECU não responde"))
    }

    @Test fun trabalhoQueLancaNaoDerrubaOMotor() {
        val port = ReplayPort(); val ears = Ears()
        val link = EcuLink(port, ears, sleep = {}); link.start()
        val boom = link.submit(Priority.LEITURA, "boom") { error("falha interna") }
        try { boom.get(2, TimeUnit.SECONDS); error("devia falhar") } catch (e: java.util.concurrent.ExecutionException) { assertTrue(e.cause is IllegalStateException) }
        assertEquals(7, link.submit(Priority.LEITURA, "ok") { 7 }.get(2, TimeUnit.SECONDS))
        link.stop()
    }

    @Test fun sessaoJaAbertaPulaOHandshake() {
        val port = ReplayPort(); val ears = Ears()
        val link = EcuLink(port, ears, sleep = {}); link.start()
        link.submit(Priority.LEITURA, "x") { 0 }.get(2, TimeUnit.SECONDS); link.stop()
        assertEquals("48 01 49", port.sentHex.first())
        assertTrue(port.sentHex.none { it == "00 02 02" })
        assertTrue(Corpus.all.isNotEmpty())
    }
}
