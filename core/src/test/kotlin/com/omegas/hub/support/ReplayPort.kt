package com.omegas.hub.support

import com.omegas.hub.ecu.Mp48Frames
import com.omegas.hub.usb.SerialPort
import java.io.File
import java.util.zip.GZIPInputStream

/** Uma transação real do corpus Lognovo (ProgBase ↔ ECU). */
data class Recorded(val sequence: Int, val request: String, val response: String)

object Corpus {
    private val file = File("../fixtures/portmon/progbase-lognovo-transactions.jsonl.gz")
    private val field = Regex(""""(sequence|request|response)":\s*("(?:[^"]*)"|\d+)""")

    val all: List<Recorded> by lazy {
        GZIPInputStream(file.inputStream()).bufferedReader().readLines().map { line ->
            val m = field.findAll(line).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
            Recorded(m.getValue("sequence").toInt(), m.getValue("request"), m.getValue("response"))
        }
    }

    /** Primeira resposta registrada para um pedido exato (em hex "AA BB"). */
    fun responseTo(request: ByteArray): String? =
        all.firstOrNull { it.request == Mp48Frames.hex(request) && it.response.isNotEmpty() }?.response

    fun bytes(hex: String) = if (hex.isEmpty()) ByteArray(0) else hex.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}

/**
 * Porta que responde com o que a ECU real respondeu ao ProgBase. Se o Hub mandar um byte que o
 * ProgBase nunca mandou, não há resposta: o teste falha. É assim que "bytes sagrados" vira prova.
 */
class ReplayPort(private val answer: (ByteArray) -> ByteArray? = { Corpus.responseTo(it)?.let(Corpus::bytes) }) : SerialPort {
    val sent = mutableListOf<ByteArray>()
    private var pending = ByteArray(0)
    var opened = 0

    override fun open() { opened++ }
    override fun close() {}
    override fun write(bytes: ByteArray) {
        sent += bytes
        pending = answer(bytes) ?: ByteArray(0)
    }
    override fun read(into: ByteArray, timeoutMs: Int): Int {
        val n = minOf(into.size, pending.size)
        System.arraycopy(pending, 0, into, 0, n)
        pending = pending.copyOfRange(n, pending.size)
        return n
    }
    val sentHex get() = sent.map(Mp48Frames::hex)
}

/** Porta roteirizada: respostas em hex por pedido, para casos que o corpus não tem (falhas). */
class ScriptedPort(private val script: Map<String, List<String>>) : SerialPort {
    private val used = HashMap<String, Int>()
    val sent = mutableListOf<String>()
    private var pending = ByteArray(0)
    override fun open() {}
    override fun close() {}
    override fun write(bytes: ByteArray) {
        val key = Mp48Frames.hex(bytes); sent += key
        val answers = script[key] ?: emptyList()
        val i = used.getOrDefault(key, 0); used[key] = i + 1
        pending = Corpus.bytes(answers.getOrNull(minOf(i, answers.size - 1)) ?: "")
    }
    override fun read(into: ByteArray, timeoutMs: Int): Int {
        val n = minOf(into.size, pending.size)
        System.arraycopy(pending, 0, into, 0, n); pending = pending.copyOfRange(n, pending.size); return n
    }
}

/** Resposta ACK completa para um pedido: eco + 53 + LEN + payload + ck. */
fun ackFor(request: ByteArray, payload: ByteArray): String {
    val body = byteArrayOf(0x53, payload.size.toByte()) + payload
    return Mp48Frames.hex(request + body + Mp48Frames.checksum(body))
}
