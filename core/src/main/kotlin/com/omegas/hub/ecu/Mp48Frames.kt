package com.omegas.hub.ecu

/**
 * Os bytes que vão para a ECU. Cada função devolve o pedido completo, já com checksum.
 * Fonte: docs/spec/mp48-frames.md. Provados byte a byte contra o corpus real em Mp48FramesTest.
 */
object Mp48Frames {
    fun checksum(body: ByteArray): Byte = (body.sumOf { it.toInt() and 0xFF } and 0xFF).toByte()

    private fun frame(vararg body: Int): ByteArray {
        val b = ByteArray(body.size) { body[it].toByte() }
        return b + checksum(b)
    }

    // Sessão
    val INIT_1 = frame(0x00, 0x02)
    val INIT_2 = frame(0x01, 0x00, 0x3A)
    val IDENTIFY = frame(0x00, 0x25)
    val CLOSE = frame(0x00, 0x01)
    val TELEMETRY = frame(0x48, 0x01)
    val NATIVE_STATUS = frame(0x48, 0x0B)

    // Leituras (endereço little-endian)
    fun readScalar(addr: Int) = frame(0x09, addr and 0xFF, addr shr 8)
    fun readVector(addr: Int) = frame(0x29, addr and 0xFF, addr shr 8)
    fun readIndexed(addr: Int, index: Int) = frame(0x0A, addr and 0xFF, addr shr 8, index)
    fun readMapRow(row: Int) = frame(0x2A, 0x54, 0x00, row)

    // Escritas
    fun writeU8(addr: Int, value: Int) = frame(0x12, addr and 0xFF, addr shr 8, value and 0xFF)
    fun writeU16Indexed(addr: Int, index: Int, value: Int) =
        frame(0x14, addr and 0xFF, addr shr 8, index, value and 0xFF, (value shr 8) and 0xFF)
    fun writeMapCell(row: Int, col: Int, value: Int) = frame(0x14, 0x54, 0x00, row, col, value and 0xFF)
    fun insertionMode(on: Boolean) = frame(0x35, 0x03, 0x00, 0x86, if (on) 0x2C else 0x24, 0x51, 0x10)
    fun autoCalAction(mode: Int) = frame(0x02, 0x24, 0x04, mode)

    fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
