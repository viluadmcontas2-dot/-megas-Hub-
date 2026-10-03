package com.omegas.hub.usb

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort as Driver

/** A porta real: CP210x via usb-serial-for-android, 9600 8N1, DTR/RTS desligados (ecu-link.md §1). */
class UsbSerialPort(private val driver: UsbSerialDriver, private val connection: UsbDeviceConnection) : SerialPort {
    private val port: Driver = driver.ports.first()

    override fun open() {
        port.open(connection)
        port.setParameters(9600, 8, Driver.STOPBITS_1, Driver.PARITY_NONE)
        port.dtr = false
        port.rts = false
        runCatching { port.purgeHwBuffers(true, true) }
    }

    override fun write(bytes: ByteArray) = port.write(bytes, WRITE_TIMEOUT_MS)

    override fun read(into: ByteArray, timeoutMs: Int): Int = port.read(into, timeoutMs)

    override fun close() { runCatching { port.close() } }

    private companion object { const val WRITE_TIMEOUT_MS = 200 }
}
