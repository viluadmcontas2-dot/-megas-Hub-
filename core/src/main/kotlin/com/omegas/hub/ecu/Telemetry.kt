package com.omegas.hub.ecu

/** Combustível como o dono vê (docs/spec/ux.md §7). */
enum class Fuel { GASOLINA, GNV, TRANSICAO, CUTOFF, DESLIGADO, DESCONHECIDO }

/** Um quadro `48 01 49` decodificado (docs/spec/mp48-frames.md §2). Unidades físicas, nunca brutos. */
data class Telemetry(
    val rpm: Int,
    val gasMs: Double,
    val petrolMs: Double,
    val fuel: Fuel,
    val waterC: Int,
    val gasC: Int,
    val levelRaw: Int,
    val gasBar: Double,
    val mapBar: Double,
    val atMs: Long,
) {
    companion object {
        const val PAYLOAD_SIZE = 34
        private const val MS_PER_RAW = 0.00256

        fun decode(p: ByteArray, atMs: Long): Telemetry? {
            if (p.size < PAYLOAD_SIZE) return null
            fun u8(i: Int) = p[i].toInt() and 0xFF
            fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
            fun s16(i: Int) = u16(i).toShort().toInt()
            val rpm = u16(0)
            val gasRaw = u16(6)
            val petrolMs = u16(8) * MS_PER_RAW
            val mapBar = s16(17) / 1000.0
            val fuel = when {
                rpm >= 1200 && petrolMs < 0.70 && gasRaw == 0 && mapBar < 0.35 -> Fuel.CUTOFF
                else -> fuelOf(u8(11))
            }
            return Telemetry(
                rpm = rpm,
                gasMs = gasRaw * MS_PER_RAW,
                petrolMs = petrolMs,
                fuel = fuel,
                waterC = 109 - u8(12),
                gasC = u8(16) - 20,
                levelRaw = u8(13),
                gasBar = u16(14) / 800.0,
                mapBar = mapBar,
                atMs = atMs,
            )
        }

        /** Byte 11: 0x80/0xA0 gasolina · 0x88/0xA8 transição · 0x90/0xB0 GNV · 0x00 desligado. */
        fun fuelOf(b: Int): Fuel = when {
            b == 0 -> Fuel.DESLIGADO
            b and 0x87 != 0x80 -> Fuel.DESCONHECIDO
            else -> when (b and 0x18) { 0x00 -> Fuel.GASOLINA; 0x08 -> Fuel.TRANSICAO; 0x10 -> Fuel.GNV; else -> Fuel.DESCONHECIDO }
        }
    }
}
