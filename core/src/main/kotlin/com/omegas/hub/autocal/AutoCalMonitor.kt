package com.omegas.hub.autocal

import com.omegas.hub.calibration.Outcome
import com.omegas.hub.calibration.Trace
import com.omegas.hub.ecu.Exchange
import com.omegas.hub.ecu.Mp48Frames
import com.omegas.hub.ecu.Reply

/** Época = (contador de AutoMatch, flag 13 do status nativo). Muda → a ECU refez o AutoMatch. */
data class Epoch(val automatchCount: Int, val flag13: Int)

/** Leitura coerente de um grupo de campos: todos na mesma época, sem escrita no meio. */
data class Snapshot(val epoch: Epoch, val values: Map<Field, IntArray>, val readAtMs: Long, val partial: Boolean)

/**
 * Observa o AutoCal (docs/spec/autocal.md §4) e executa as poucas ações permitidas (§3), com testemunha.
 * Observar nunca escreve. Cada método roda dentro de um trabalho da fila (acesso exclusivo).
 */
class AutoCalMonitor(private val ex: Exchange, private val clock: () -> Long = System::currentTimeMillis,
                     private val sleep: (Long) -> Unit = Thread::sleep) {

    fun epoch(): Epoch? {
        val status = (ex.send(Mp48Frames.NATIVE_STATUS) as? Reply.Ack)?.payload ?: return null
        val count = (ex.send(Field.AUTOMATCH_COUNT.request) as? Reply.Ack)?.payload ?: return null
        if (status.size < 14 || count.isEmpty()) return null
        return Epoch(count[0].toInt() and 0xFF, status[12].toInt() and 0xFF)
    }

    /** Lê [fields] com guarda de época: época igual antes e depois, senão `null` (relê no próximo tick). */
    fun snapshot(fields: List<Field>): Snapshot? {
        val before = epoch() ?: return null
        val values = LinkedHashMap<Field, IntArray>()
        var partial = false
        for (f in fields) {
            when (val r = ex.sendWithRetry(f.request)) {
                is Reply.Ack -> values[f] = f.decode(r.payload)
                else -> partial = true
            }
        }
        val after = epoch() ?: return null
        if (after != before) return null
        return Snapshot(before, values, clock(), partial)
    }

    fun setEnabled(on: Boolean): Outcome = Trace(ex).run({ if (on) "AutoCal retomado." else "AutoCal pausado." }) {
        ack(Mp48Frames.writeU8(Field.ENABLE.addr, if (on) 1 else 0), if (on) "ligar AutoCal" else "desligar AutoCal")
        val read = Field.ENABLE.decode(ack(Field.ENABLE.request, "testemunha"))
        if (read.firstOrNull() != (if (on) 1 else 0)) throw com.omegas.hub.calibration.Mismatch("A ECU não confirmou o estado do AutoCal.")
    }

    /** Ação + 1000 ms de assentamento + testemunha: contagens e zonas do combustível zeradas. */
    fun act(action: AutoCalAction): Outcome = Trace(ex).run({ "${action.text}: a ECU confirmou." }) {
        ack(action.request, action.text)
        sleep(SETTLE_MS)
        val (counts, zones) = if (action == AutoCalAction.RESET_GAS) Field.COUNT_GAS to Field.ZONES_GAS else Field.COUNT_PETROL to Field.ZONES_PETROL
        val c = counts.decode(ack(counts.request, "testemunha contagens"))
        val z = zones.decode(ack(zones.request, "testemunha zonas"))
        if (c.any { it != 0 } || z.any { it != 0 }) throw com.omegas.hub.calibration.Mismatch("A ECU não zerou a aquisição (${c.count { it != 0 }} bandas ainda com dados).")
    }

    companion object { const val SETTLE_MS = 1_000L }
}
