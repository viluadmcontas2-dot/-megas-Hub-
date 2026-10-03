package com.omegas.hub.calibration

import com.omegas.hub.ecu.Exchange
import com.omegas.hub.ecu.Mp48Frames

/** Curva K: 30 fatores Q14 sobre 30 tempos de injeção (docs/spec/curve-k.md). Tudo em bruto aqui. */
data class CurveK(val axisRaw: IntArray, val factorsRaw: IntArray) {
    val axisMs get() = axisRaw.map { it / 512.0 }
    val factors get() = factorsRaw.map { it / Q14 }

    companion object {
        const val POINTS = 30
        const val Q14 = 16384.0
        const val NEUTRAL = 0x4000
        const val ADDR_FACTORS = 0x0161
        const val ADDR_AXIS = 0x014B
        const val MIN_SAFE_FACTOR = 0.60
        const val MAX_RAW = 0xFFFF

        /** Fator → bruto Q14, truncado para zero e preso à faixa segura. */
        fun rawOf(factor: Double): Int {
            require(factor >= MIN_SAFE_FACTOR) { "Fator %.3f abaixo do mínimo seguro 0,60.".format(factor) }
            return minOf((factor * Q14).toInt(), MAX_RAW)
        }

        fun decode(p: ByteArray): IntArray {
            require(p.size >= POINTS * 2) { "Curva com ${p.size} bytes; esperava 60." }
            return IntArray(POINTS) { u16le(p, it * 2) }
        }
    }
}

/** Lê, fotografa, grava e confere a Curva K. Cada método é uma operação inteira dentro da fila. */
class CurveKWriter(private val ex: Exchange) {

    fun read(): CurveK = Trace(ex).let {
        CurveK(CurveK.decode(it.ack(Mp48Frames.readVector(CurveK.ADDR_AXIS), "ler eixo")),
               CurveK.decode(it.ack(Mp48Frames.readVector(CurveK.ADDR_FACTORS), "ler fatores")))
    }

    /** Grava os pontos pedidos. Foto antes; readback depois, byte a byte. */
    fun write(points: Map<Int, Int>): Written {
        var photo: CurveK? = null
        val outcome = Trace(ex).run({ "Gravado: ${points.size} ponto(s) confirmados pela ECU." }) {
            require(points.keys.all { it in 0 until CurveK.POINTS }) { "Índice fora de 0..29." }
            require(points.values.all { it in CurveK.rawOf(CurveK.MIN_SAFE_FACTOR)..CurveK.MAX_RAW }) { "Fator fora da faixa segura." }
            val before = read(); photo = before
            note("foto: ${Mp48Frames.hex(before.factorsRaw.toLeBytes())}")
            for ((i, raw) in points.toSortedMap()) ack(Mp48Frames.writeU16Indexed(CurveK.ADDR_FACTORS, i, raw), "gravar ponto $i")
            val after = CurveK.decode(ack(Mp48Frames.readVector(CurveK.ADDR_FACTORS), "readback"))
            for (i in 0 until CurveK.POINTS) {
                val expected = points[i] ?: before.factorsRaw[i]
                if (after[i] != expected) throw Mismatch("A ECU não confirmou o ponto $i (esperado %04X, lido %04X).".format(expected, after[i]))
            }
        }
        return Written(outcome, photo)
    }

    /** Todos os 30 pontos em 1,000 (0x4000), em ordem, e readback. */
    fun reset(): Written = write((0 until CurveK.POINTS).associateWith { CurveK.NEUTRAL })

    /** Volta a uma foto/backup: grava só o que difere. Recusa se o eixo mudou. */
    fun restore(target: CurveK): Written {
        val current = try { read() } catch (e: Exception) {
            return Written(Outcome.Failed(FailureKind.TRANSPORTE, "Não consegui ler a curva atual.", listOf(e.toString())), null)
        }
        if (!current.axisRaw.contentEquals(target.axisRaw)) {
            return Written(Outcome.Failed(FailureKind.APP, "O eixo da ECU mudou; este backup não serve.", emptyList()), null)
        }
        val diff = (0 until CurveK.POINTS).filter { current.factorsRaw[it] != target.factorsRaw[it] }.associateWith { target.factorsRaw[it] }
        if (diff.isEmpty()) return Written(Outcome.Done("A curva já é igual ao backup.", emptyList()), current)
        return write(diff)
    }

    data class Written(val outcome: Outcome, val photo: CurveK?)
}

internal fun IntArray.toLeBytes() = ByteArray(size * 2).also { b -> forEachIndexed { i, v -> b[i * 2] = v.toByte(); b[i * 2 + 1] = (v shr 8).toByte() } }
