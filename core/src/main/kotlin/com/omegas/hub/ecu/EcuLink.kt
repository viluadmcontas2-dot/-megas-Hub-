package com.omegas.hub.ecu

import com.omegas.hub.usb.SerialPort
import java.util.concurrent.CompletableFuture
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class LinkState { SEM_CABO, CONECTANDO, CONECTADO }

/** Quem fala primeiro na fila (docs/spec/ecu-link.md §7). Menor = antes. */
enum class Priority { SEGURANCA, MUTACAO, LEITURA }

/** O que o resto do app ouve do motor. Chamado na thread do motor: devolver rápido. */
interface LinkListener {
    fun onState(state: LinkState, text: String)
    fun onTelemetry(frame: Telemetry)
    /** Falha de transporte que o dono deve ver (depois de esgotar tentativas). */
    fun onFault(text: String, technical: String)
}

/**
 * A única autoridade sobre a porta. Uma thread, uma transação por vez.
 * Trabalhos entram por [submit] e rodam com acesso exclusivo à ECU; entre trabalhos, telemetria.
 */
class EcuLink(
    private val port: SerialPort,
    private val listener: LinkListener,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private class Job<T>(val priority: Priority, val seq: Long, val name: String,
                         val block: (Exchange) -> T, val future: CompletableFuture<T>) : Comparable<Job<*>> {
        override fun compareTo(other: Job<*>) = compareValuesBy(this, other, { it.priority }, { it.seq })
    }

    private val queue = PriorityBlockingQueue<Job<*>>()
    private val seq = AtomicLong()
    private val exchange = Exchange(port)
    @Volatile var state = LinkState.SEM_CABO; private set
    @Volatile private var running = false
    private var consecutiveFailures = 0
    private var lastGoodAtMs = 0L

    /** Agenda um trabalho com acesso exclusivo à ECU. O bloco roda na thread do motor. */
    fun <T> submit(priority: Priority, name: String, block: (Exchange) -> T): CompletableFuture<T> {
        val f = CompletableFuture<T>()
        queue.add(Job(priority, seq.incrementAndGet(), name, block, f))
        return f
    }

    fun start(): Thread = Thread(::loop, "ecu-link").also { running = true; it.isDaemon = true; it.start() }

    fun stop() {
        running = false
    }

    private fun setState(s: LinkState, text: String) {
        if (state != s) { state = s; listener.onState(s, text) }
    }

    private fun loop() {
        try {
            port.open()
        } catch (e: Exception) {
            listener.onFault("Não consegui abrir o cabo.", e.toString()); return
        }
        setState(LinkState.CONECTANDO, "Ligando à ECU…")
        var attempt = 0
        while (running) {
            when (state) {
                LinkState.CONECTANDO -> if (handshake()) {
                    attempt = 0; consecutiveFailures = 0
                    setState(LinkState.CONECTADO, "ECU ligada")
                } else {
                    attempt++
                    if (attempt >= HANDSHAKE_MAX_ATTEMPTS) {
                        listener.onFault("ECU não responde. Confira o cabo e a chave na posição ligada.", exchange.technicalDetail())
                        attempt = 0
                    }
                    sleep(minOf(250L shl minOf(attempt, 4), 5_000L))
                }
                LinkState.CONECTADO -> connectedTick()
                LinkState.SEM_CABO -> return
            }
        }
        submitClose()
        try { port.close() } catch (_: Exception) {}
        setState(LinkState.SEM_CABO, "Sem cabo")
    }

    /** Sessão já aberta responde telemetria; senão o aperto de mão do ProgBase (ecu-link §4). */
    private fun handshake(): Boolean {
        if (exchange.send(Mp48Frames.TELEMETRY) is Reply.Ack) return true
        return listOf(Mp48Frames.INIT_1, Mp48Frames.INIT_2, Mp48Frames.IDENTIFY).all { exchange.sendWithRetry(it) is Reply.Ack }
    }

    private fun connectedTick() {
        val job = queue.poll(TELEMETRY_GAP_MS, TimeUnit.MILLISECONDS)
        if (job != null) { run(job); return }
        when (val r = exchange.send(Mp48Frames.TELEMETRY)) {
            is Reply.Ack -> {
                consecutiveFailures = 0; lastGoodAtMs = clock()
                Telemetry.decode(r.payload, lastGoodAtMs)?.let(listener::onTelemetry)
            }
            is Reply.EcuError -> consecutiveFailures = 0 // a ECU falou: linha viva
            is Reply.Transport -> transportFailure(r)
        }
    }

    private fun <T> run(job: Job<T>) {
        try {
            job.future.complete(job.block(exchange))
        } catch (e: Exception) {
            job.future.completeExceptionally(e)
        }
    }

    private fun transportFailure(r: Reply.Transport) {
        consecutiveFailures++
        val silentMs = clock() - lastGoodAtMs
        if (consecutiveFailures >= HARD_FAILURES) {
            try { port.close(); port.open() } catch (_: Exception) {}
            consecutiveFailures = 0
            setState(LinkState.CONECTANDO, "Religando o cabo…")
        } else if (consecutiveFailures >= SOFT_FAILURES || silentMs > SILENCE_MS) {
            setState(LinkState.CONECTANDO, "ECU calada, religando…")
        }
    }

    private fun submitClose() {
        if (state == LinkState.CONECTADO) exchange.send(Mp48Frames.CLOSE)
    }

    companion object {
        const val TELEMETRY_GAP_MS = 5L
        const val SOFT_FAILURES = 3
        const val HARD_FAILURES = 10
        const val SILENCE_MS = 1_800L
        const val HANDSHAKE_MAX_ATTEMPTS = 40
    }
}

fun Exchange.technicalDetail(): String =
    "pedido ${Mp48Frames.hex(lastRequest)} · resposta ${Mp48Frames.hex(lastResponse)}"
