package com.omegas.hub.calibration

import com.omegas.hub.ecu.Exchange
import com.omegas.hub.ecu.Mp48Frames
import com.omegas.hub.ecu.Reply

enum class FailureKind { TRANSPORTE, ECU, APP }

/** Fim de uma operação na ECU. "Gravado" só existe como [Done] depois do readback (regra 3). */
sealed class Outcome {
    data class Done(val text: String, val technical: List<String>) : Outcome()
    data class Failed(val kind: FailureKind, val text: String, val technical: List<String>) : Outcome()
}

/** Registro técnico de uma operação: cada pedido e resposta, na ordem. */
class Trace(private val ex: Exchange) {
    val lines = mutableListOf<String>()

    /** Envia e exige ACK; devolve o payload ou lança [EcuRefused]/[LineFailed]. */
    fun ack(request: ByteArray, what: String): ByteArray {
        val reply = ex.sendWithRetry(request)
        lines += "$what: ${Mp48Frames.hex(ex.lastRequest)} → ${Mp48Frames.hex(ex.lastResponse)}"
        return when (reply) {
            is Reply.Ack -> reply.payload
            is Reply.EcuError -> throw EcuRefused("A ECU recusou: $what (código %02X).".format(reply.code))
            is Reply.Transport -> throw LineFailed("O cabo falhou durante: $what (${reply.reason}).")
        }
    }

    fun note(text: String) { lines += text }

    inline fun run(doneText: () -> String, body: Trace.() -> Unit): Outcome = try {
        body(); Outcome.Done(doneText(), lines)
    } catch (e: EcuRefused) {
        Outcome.Failed(FailureKind.ECU, e.message!!, lines)
    } catch (e: LineFailed) {
        Outcome.Failed(FailureKind.TRANSPORTE, e.message!!, lines)
    } catch (e: Mismatch) {
        Outcome.Failed(FailureKind.ECU, e.message!!, lines)
    } catch (e: IllegalArgumentException) {
        Outcome.Failed(FailureKind.APP, e.message ?: "Pedido inválido.", lines)
    }
}

class EcuRefused(msg: String) : Exception(msg)
class LineFailed(msg: String) : Exception(msg)
/** Readback diferente do pedido: a ECU não confirmou. */
class Mismatch(msg: String) : Exception(msg)

internal fun u16le(p: ByteArray, i: Int) = (p[i].toInt() and 0xFF) or ((p[i + 1].toInt() and 0xFF) shl 8)
