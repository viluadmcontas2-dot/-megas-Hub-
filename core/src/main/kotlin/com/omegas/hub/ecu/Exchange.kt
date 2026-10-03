package com.omegas.hub.ecu

import com.omegas.hub.usb.SerialPort

/** Como uma transação terminou. Transporte ≠ ECU (regra 4). */
sealed class Reply {
    /** ACK (0x53) com payload. */
    data class Ack(val payload: ByteArray) : Reply()
    /** A ECU respondeu, mas recusou (0xCA). `retryable` = código 0x08. */
    data class EcuError(val code: Int) : Reply() { val retryable get() = code == 0x08 }
    /** A linha falhou: silêncio, eco diferente, checksum errado. */
    data class Transport(val reason: String, val raw: ByteArray) : Reply()
}

/**
 * Uma pergunta, uma resposta (docs/spec/ecu-link.md §2). Única porta de saída de bytes do Hub.
 * Lê o envelope em etapas: eco · status · LEN · payload · checksum.
 */
class Exchange(private val port: SerialPort, private val timeoutMs: Int = 240) {
    var lastRequest: ByteArray = ByteArray(0); private set
    var lastResponse: ByteArray = ByteArray(0); private set

    fun send(request: ByteArray): Reply {
        lastRequest = request
        port.write(request)
        val echo = readExact(request.size)
        if (echo.size < request.size) return transport(if (echo.isEmpty()) "silêncio" else "eco incompleto", echo)
        if (!echo.contentEquals(request)) return transport("eco diferente", echo)
        val head = readExact(2)
        if (head.size < 2) return transport("sem status", echo + head)
        val status = head[0].toInt() and 0xFF
        val len = head[1].toInt() and 0xFF
        val tail = readExact(len + 1)
        lastResponse = echo + head + tail
        if (tail.size < len + 1) return transport("payload incompleto", lastResponse)
        val payload = tail.copyOf(len)
        val expected = (status + len + payload.sumOf { it.toInt() and 0xFF }) and 0xFF
        if (expected != (tail[len].toInt() and 0xFF)) return transport("checksum errado", lastResponse)
        return when (status) {
            0x53 -> Reply.Ack(payload)
            0xCA -> Reply.EcuError(payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0)
            else -> transport("status desconhecido %02X".format(status), lastResponse)
        }
    }

    /** Envia e, se a ECU pedir (0xCA 08), repete uma vez. */
    fun sendWithRetry(request: ByteArray): Reply {
        val first = send(request)
        return if (first is Reply.EcuError && first.retryable) send(request) else first
    }

    private fun readExact(n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val chunk = ByteArray(n - got)
            val r = port.read(chunk, timeoutMs)
            if (r <= 0) break
            System.arraycopy(chunk, 0, buf, got, r)
            got += r
        }
        return if (got == n) buf else buf.copyOf(got)
    }

    private fun transport(reason: String, raw: ByteArray): Reply.Transport {
        lastResponse = raw
        return Reply.Transport(reason, raw)
    }
}
