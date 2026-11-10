package com.odit.obd.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat

data class FoundDevice(
    val address: String,
    val name: String,
    val seenBle: Boolean,
    val seenClassic: Boolean,
    val bonded: Boolean,
    val rssi: Int?,
) {
    val looksLikeObd: Boolean
        get() = OBD_NAME_HINTS.any { name.contains(it, ignoreCase = true) }

    companion object {
        val OBD_NAME_HINTS = listOf(
            "MUCAR", "BT200", "OBD", "ELM", "LINK", "VEEPEAK", "VGATE", "ICAR",
            "KONNWEI", "DIAG", "SCAN", "THINK", "CARLY", "LELINK",
        )
    }
}

/** Finds paired devices, BLE advertisers and classic devices in discovery mode. */
@SuppressLint("MissingPermission")
class DeviceScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter,
) {
    private val devices = LinkedHashMap<String, FoundDevice>()
    private var onUpdate: ((List<FoundDevice>) -> Unit)? = null
    private var receiver: BroadcastReceiver? = null
    private var bleRunning = false

    private val leCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: safeName(result.device)
            merge(result.device, name, ble = true, classic = false, rssi = result.rssi)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }
    }

    fun start(onUpdate: (List<FoundDevice>) -> Unit) {
        stop()
        this.onUpdate = onUpdate
        synchronized(devices) { devices.clear() }

        runCatching {
            adapter.bondedDevices?.forEach { d ->
                val t = d.type
                merge(
                    d, safeName(d),
                    ble = t == BluetoothDevice.DEVICE_TYPE_LE || t == BluetoothDevice.DEVICE_TYPE_DUAL,
                    classic = t != BluetoothDevice.DEVICE_TYPE_LE,
                    rssi = null,
                )
            }
        }

        runCatching {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            adapter.bluetoothLeScanner?.startScan(null, settings, leCallback)
            bleRunning = true
        }

        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_FOUND) return
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                device ?: return
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                merge(
                    device, safeName(device),
                    ble = device.type == BluetoothDevice.DEVICE_TYPE_LE,
                    classic = device.type != BluetoothDevice.DEVICE_TYPE_LE,
                    rssi = if (rssi == Short.MIN_VALUE) null else rssi.toInt(),
                )
            }
        }
        receiver = r
        runCatching {
            ContextCompat.registerReceiver(
                context, r, IntentFilter(BluetoothDevice.ACTION_FOUND), ContextCompat.RECEIVER_EXPORTED,
            )
            adapter.startDiscovery()
        }
    }

    fun stop() {
        if (bleRunning) {
            runCatching { adapter.bluetoothLeScanner?.stopScan(leCallback) }
            bleRunning = false
        }
        runCatching { adapter.cancelDiscovery() }
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    fun deviceFor(address: String): BluetoothDevice? = runCatching { adapter.getRemoteDevice(address) }.getOrNull()

    private fun safeName(d: BluetoothDevice): String = runCatching { d.name }.getOrNull().orEmpty()

    private fun merge(d: BluetoothDevice, name: String, ble: Boolean, classic: Boolean, rssi: Int?) {
        val list: List<FoundDevice>
        synchronized(devices) {
            val old = devices[d.address]
            val bonded = runCatching { d.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
            val finalName = name.ifBlank { old?.name.orEmpty() }
            if (finalName.isBlank()) return // unnamed beacons are never OBD adapters
            devices[d.address] = FoundDevice(
                address = d.address,
                name = finalName,
                seenBle = ble || (old?.seenBle ?: false),
                seenClassic = classic || (old?.seenClassic ?: false),
                bonded = bonded || (old?.bonded ?: false),
                rssi = rssi ?: old?.rssi,
            )
            list = devices.values.sortedWith(
                compareByDescending<FoundDevice> { it.looksLikeObd }
                    .thenByDescending { it.bonded }
                    .thenByDescending { it.rssi ?: -200 },
            )
        }
        onUpdate?.invoke(list)
    }
}
