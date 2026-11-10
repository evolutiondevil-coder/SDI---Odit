package com.odit.obd.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** Bluetooth classic (SPP / RFCOMM) link, used by most "ELM327" dongles. */
@SuppressLint("MissingPermission")
class ClassicTransport(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
) : ObdTransport {

    override val description = "Bluetooth (класически)"

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<ByteArray> = channel

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closed = false

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        runCatching { adapter.cancelDiscovery() }
        val factories: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                // Fallback used by many OBD apps for adapters with broken SDP records.
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            },
        )
        var lastError: Exception? = null
        for (factory in factories) {
            if (closed) throw TransportException("Връзката е прекратена")
            var s: BluetoothSocket? = null
            try {
                s = factory()
                s.connect()
                socket = s
                startReader(s)
                return@withContext
            } catch (e: Exception) {
                lastError = e
                runCatching { s?.close() }
            }
        }
        throw TransportException(
            "Неуспешна Bluetooth връзка (${lastError?.message ?: "неизвестна грешка"})",
            lastError,
        )
    }

    private fun startReader(s: BluetoothSocket) {
        val thread = Thread({
            val buf = ByteArray(1024)
            try {
                val input = s.inputStream
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) channel.trySend(buf.copyOf(n))
                }
            } catch (_: IOException) {
            } finally {
                channel.close(TransportException("Връзката с адаптера прекъсна"))
            }
        }, "obd-classic-reader")
        thread.isDaemon = true
        thread.start()
    }

    override suspend fun write(data: ByteArray): Unit = withContext(Dispatchers.IO) {
        val s = socket ?: throw TransportException("Няма връзка с адаптера")
        try {
            s.outputStream.write(data)
            s.outputStream.flush()
        } catch (e: IOException) {
            throw TransportException("Грешка при изпращане към адаптера", e)
        }
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
        socket = null
        channel.close()
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
