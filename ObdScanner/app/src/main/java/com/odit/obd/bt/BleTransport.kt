package com.odit.obd.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Bluetooth Low Energy link. BLE OBD dongles expose a "serial port" as a pair of
 * GATT characteristics (one to write commands, one that notifies responses).
 * Different vendors use different UUIDs, so we try the well-known ones first and
 * then probe every notify/write pair until one answers like an ELM327.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : ObdTransport {

    override val description = "Bluetooth LE"

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<ByteArray> = channel

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var notifyUuid: UUID? = null
    @Volatile private var chunkSize = 20
    @Volatile private var ready = false
    @Volatile private var closed = false

    @Volatile private var connectedSignal = CompletableDeferred<Unit>()
    @Volatile private var servicesSignal = CompletableDeferred<Int>()
    @Volatile private var opSignal: CompletableDeferred<Int>? = null
    @Volatile private var mtuSignal: CompletableDeferred<Int>? = null

    private val opMutex = Mutex()

    private data class Candidate(
        val notify: BluetoothGattCharacteristic,
        val write: BluetoothGattCharacteristic,
    )

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectedSignal.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val err = TransportException("Bluetooth LE връзката прекъсна (код $status)")
                connectedSignal.completeExceptionally(err)
                servicesSignal.completeExceptionally(err)
                opSignal?.completeExceptionally(err)
                mtuSignal?.completeExceptionally(err)
                if (ready) channel.close(err)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesSignal.complete(status)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) chunkSize = (mtu - 3).coerceIn(20, 500)
            mtuSignal?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            opSignal?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            opSignal?.complete(status)
        }

        @Deprecated("Used on Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            deliver(c.uuid, c.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(c.uuid, value)
        }
    }

    private fun deliver(uuid: UUID, value: ByteArray?) {
        if (value == null || value.isEmpty()) return
        if (uuid == notifyUuid) channel.trySend(value.copyOf())
    }

    override suspend fun connect() {
        var lastError: Throwable? = null
        repeat(3) {
            if (closed) throw TransportException("Връзката е прекратена")
            try {
                connectOnce()
                return
            } catch (e: Exception) {
                lastError = e
                releaseGatt()
                delay(700)
            }
        }
        throw TransportException(
            "Неуспешна Bluetooth LE връзка (${lastError?.message ?: "неизвестна грешка"})",
            lastError,
        )
    }

    private suspend fun connectOnce() {
        connectedSignal = CompletableDeferred()
        servicesSignal = CompletableDeferred()
        val g = withContext(Dispatchers.Main) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } ?: throw TransportException("Bluetooth LE не е достъпен")
        gatt = g
        withTimeout(15_000) { connectedSignal.await() }
        delay(300)
        if (!g.discoverServices()) throw TransportException("Неуспешно откриване на услугите")
        val status = withTimeout(15_000) { servicesSignal.await() }
        if (status != BluetoothGatt.GATT_SUCCESS) throw TransportException("Грешка при откриване на услугите ($status)")

        // A bigger MTU lets long responses arrive in fewer packets. Not required.
        mtuSignal = CompletableDeferred()
        if (g.requestMtu(247)) {
            withTimeoutOrNull(3_000) { runCatching { mtuSignal?.await() } }
        }

        val candidates = findCandidates(g.services.orEmpty())
        if (candidates.isEmpty()) {
            throw TransportException("Устройството няма сериен канал за OBD (не е ELM327 съвместимо)")
        }
        for (c in candidates) {
            if (probe(g, c)) {
                ready = true
                return
            }
        }
        // Nothing answered the probe: keep the most likely pair and let the ELM layer report.
        activate(g, candidates.first())
        ready = true
    }

    private fun findCandidates(services: List<BluetoothGattService>): List<Candidate> {
        val result = mutableListOf<Candidate>()
        fun add(n: BluetoothGattCharacteristic?, w: BluetoothGattCharacteristic?) {
            if (n == null || w == null) return
            if (!n.canNotify() || !w.canWrite()) return
            if (result.none { it.notify === n && it.write === w }) result += Candidate(n, w)
        }
        for ((svc, notify, write) in KNOWN) {
            val s = services.firstOrNull { it.uuid == svc } ?: continue
            add(s.getCharacteristic(notify), s.getCharacteristic(write))
        }
        for (s in services) {
            if (s.uuid in IGNORED_SERVICES) continue
            val chars = s.characteristics.orEmpty()
            chars.filter { it.canNotify() && it.canWrite() }.forEach { add(it, it) }
            val notifies = chars.filter { it.canNotify() }
            val writes = chars.filter { it.canWrite() }
            for (n in notifies) for (w in writes) add(n, w)
        }
        return result
    }

    private suspend fun probe(g: BluetoothGatt, c: Candidate): Boolean {
        activate(g, c)
        drain()
        runCatching { writeRaw("ATI\r".toByteArray()) }.onFailure { return false }
        val sb = StringBuilder()
        withTimeoutOrNull(2_000) {
            while (true) {
                val chunk = channel.receive()
                sb.append(String(chunk, Charsets.ISO_8859_1))
                if (sb.contains('>')) break
            }
        }
        val text = sb.toString()
        delay(100)
        drain()
        if (text.contains('>') || text.contains("ELM", ignoreCase = true)) return true
        deactivate(g, c)
        return false
    }

    private fun drain() {
        while (channel.tryReceive().isSuccess) { /* discard */ }
    }

    private suspend fun activate(g: BluetoothGatt, c: Candidate) {
        notifyUuid = c.notify.uuid
        writeChar = c.write
        g.setCharacteristicNotification(c.notify, true)
        val cccd = c.notify.getDescriptor(CCCD) ?: return
        val value = if (c.notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        writeDescriptor(g, cccd, value)
    }

    private suspend fun deactivate(g: BluetoothGatt, c: Candidate) {
        g.setCharacteristicNotification(c.notify, false)
        val cccd = c.notify.getDescriptor(CCCD) ?: return
        writeDescriptor(g, cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
    }

    private suspend fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        opMutex.withLock {
            val signal = CompletableDeferred<Int>()
            opSignal = signal
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
            if (started) withTimeoutOrNull(3_000) { runCatching { signal.await() } }
        }
    }

    override suspend fun write(data: ByteArray) {
        if (!ready) throw TransportException("Няма връзка с адаптера")
        writeRaw(data)
    }

    private suspend fun writeRaw(data: ByteArray) {
        val g = gatt ?: throw TransportException("Няма връзка с адаптера")
        val c = writeChar ?: throw TransportException("Няма канал за запис")
        val withResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val type = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        var offset = 0
        while (offset < data.size) {
            val end = minOf(data.size, offset + chunkSize)
            val chunk = data.copyOfRange(offset, end)
            opMutex.withLock {
                var attempt = 0
                while (true) {
                    val signal = CompletableDeferred<Int>()
                    opSignal = signal
                    val started = if (Build.VERSION.SDK_INT >= 33) {
                        g.writeCharacteristic(c, chunk, type) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        c.writeType = type
                        @Suppress("DEPRECATION")
                        c.value = chunk
                        @Suppress("DEPRECATION")
                        g.writeCharacteristic(c)
                    }
                    if (started) {
                        withTimeoutOrNull(if (withResponse) 2_000 else 300) { runCatching { signal.await() } }
                        break
                    }
                    attempt++
                    if (attempt > 10) throw TransportException("Неуспешен запис по Bluetooth LE")
                    delay(30)
                }
            }
            offset = end
        }
    }

    private fun releaseGatt() {
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    override fun close() {
        closed = true
        ready = false
        releaseGatt()
        channel.close()
    }

    private fun BluetoothGattCharacteristic.canNotify() =
        properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

    private fun BluetoothGattCharacteristic.canWrite() =
        properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

    companion object {
        private fun uuid16(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

        private val CCCD = uuid16("2902")

        /** (service, notify characteristic, write characteristic) used by common BLE OBD adapters. */
        private val KNOWN: List<Triple<UUID, UUID, UUID>> = listOf(
            Triple(uuid16("fff0"), uuid16("fff1"), uuid16("fff2")),
            Triple(uuid16("ffe0"), uuid16("ffe1"), uuid16("ffe1")),
            Triple(uuid16("ffe0"), uuid16("ffe1"), uuid16("ffe2")),
            Triple(uuid16("18f0"), uuid16("2af0"), uuid16("2af1")),
            Triple(
                UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
            ),
            Triple(
                UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"),
                UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
                UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
            ),
            Triple(uuid16("ae00"), uuid16("ae02"), uuid16("ae01")),
        )

        /** Standard services that are never the serial channel. */
        private val IGNORED_SERVICES = setOf(
            uuid16("1800"), uuid16("1801"), uuid16("180a"), uuid16("180f"), uuid16("fe59"),
        )
    }
}
