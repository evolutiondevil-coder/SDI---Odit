package com.odit.obd.bt

import kotlinx.coroutines.channels.ReceiveChannel
import java.io.IOException

/**
 * A byte pipe to an ELM327-compatible adapter (Bluetooth classic, BLE or the demo simulator).
 */
interface ObdTransport {
    /** Human readable type of link, e.g. "Bluetooth LE". */
    val description: String

    /** Raw bytes coming from the adapter. Closed when the link drops. */
    val incoming: ReceiveChannel<ByteArray>

    suspend fun connect()

    suspend fun write(data: ByteArray)

    fun close()
}

class TransportException(message: String, cause: Throwable? = null) : IOException(message, cause)
