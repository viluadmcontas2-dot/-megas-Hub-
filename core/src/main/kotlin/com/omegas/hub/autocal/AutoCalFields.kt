package com.omegas.hub.autocal

import com.omegas.hub.ecu.Mp48Frames

/** Campos AutoCal da ECU (docs/spec/autocal.md §2). Só os que o Hub lê; nada decorativo. */
enum class Field(val addr: Int, val vector: Boolean, val signed: Boolean = false, val width: Int = 2) {
    ENABLE(0x014A, false, width = 1),
    AXIS_MS(0x014B, true),
    MAP_THRESHOLDS(0x014C, true, signed = true),
    COUNT_PETROL(0x015B, true, width = 1),
    COUNT_GAS(0x015C, true, width = 1),
    INJ_GAS_PREV(0x015D, true),
    MAP_GAS_PREV(0x015E, true, signed = true),
    INJ_GAS(0x015F, true),
    MAP_GAS(0x0160, true, signed = true),
    FACTORS(0x0161, true),
    INJ_PETROL(0x0162, true),
    MAP_PETROL(0x0163, true, signed = true),
    ZONES_PETROL(0x016F, true, width = 1),
    ZONES_GAS(0x0170, true, width = 1),
    CALIBRATION(0x0172, true, width = 1),
    AUTOMATCH_COUNT(0x0174, false, width = 1),
    MAP_PETROL_PER_POINT(0x018D, true, signed = true),
    MAP_GAS_PER_POINT(0x018E, true, signed = true);

    val request: ByteArray get() = if (vector) Mp48Frames.readVector(addr) else Mp48Frames.readScalar(addr)

    /** Payload bruto → valores inteiros conforme largura e sinal. */
    fun decode(p: ByteArray): IntArray {
        val n = p.size / width
        return IntArray(n) { i ->
            if (width == 1) p[i].toInt() and 0xFF
            else {
                val v = (p[2 * i].toInt() and 0xFF) or ((p[2 * i + 1].toInt() and 0xFF) shl 8)
                if (signed) v.toShort().toInt() else v
            }
        }
    }

    companion object {
        const val BANDS = 18
        const val POINTS = 30
        fun ms(raw: Int) = raw / 512.0
        fun bar(raw: Int) = raw / 1024.0
        fun factor(raw: Int) = raw / 16384.0

        /** Leitura por tick curto: o que muda enquanto o carro anda. */
        val ACQUISITION = listOf(COUNT_PETROL, COUNT_GAS, INJ_PETROL, MAP_PETROL, INJ_GAS, MAP_GAS, ZONES_PETROL, ZONES_GAS)
        /** Leitura lenta: só muda quando a ECU faz AutoMatch ou o dono grava. */
        val REFERENCE = listOf(FACTORS, AXIS_MS, MAP_THRESHOLDS, INJ_GAS_PREV, MAP_GAS_PREV, MAP_PETROL_PER_POINT, MAP_GAS_PER_POINT, CALIBRATION, ENABLE)
    }
}

/** Ações `02 24 04 modo` expostas (reset total e AutoMatch manual ficam fora de propósito). */
enum class AutoCalAction(val mode: Int, val text: String) {
    RESET_PETROL(0x01, "Zerar aquisição em gasolina"),
    RESET_GAS(0x02, "Zerar aquisição em gás");
    val request: ByteArray get() = Mp48Frames.autoCalAction(mode)
}
