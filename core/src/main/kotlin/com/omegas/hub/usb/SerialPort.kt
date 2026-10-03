package com.omegas.hub.usb

/**
 * Porta serial mínima. O Android implementa com usb-serial-for-android; os testes implementam com
 * o corpus real (ReplayPort). Tudo o que o motor precisa: escrever bytes e ler com prazo.
 */
interface SerialPort {
    /** Abre a 9600 8N1, DTR e RTS desligados, buffers limpos. Lança em falha de USB. */
    fun open()
    fun write(bytes: ByteArray)
    /** Lê até [into].size bytes; devolve quantos chegaram antes de [timeoutMs] (0 = silêncio). */
    fun read(into: ByteArray, timeoutMs: Int): Int
    fun close()
}
