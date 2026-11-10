package com.odit.obd

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.odit.obd.ui.MainViewModel
import com.odit.obd.ui.OditApp
import com.odit.obd.ui.OditTheme
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private var pending: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                ensureEnabled()
            } else {
                pending = null
                vm.message = "Без разрешение за Bluetooth приложението не може да намери адаптера."
            }
        }

    private val enableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (bluetoothEnabled()) runPending() else {
                pending = null
                vm.message = "Включете Bluetooth, за да се свържете с адаптера."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OditTheme {
                OditApp(
                    vm = vm,
                    withBluetooth = ::withBluetooth,
                    onShare = ::share,
                    onSearch = ::searchWeb,
                    onExportLog = ::exportLog,
                )
            }
        }
    }

    private fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun withBluetooth(action: () -> Unit) {
        if (!vm.bluetoothAvailable) {
            vm.message = "Този телефон няма Bluetooth."
            return
        }
        pending = action
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) ensureEnabled() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun bluetoothEnabled(): Boolean =
        runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)

    private fun ensureEnabled() {
        if (bluetoothEnabled()) {
            runPending()
        } else {
            runCatching { enableLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                .onFailure { vm.message = "Включете Bluetooth от настройките." }
        }
    }

    private fun runPending() {
        val action = pending
        pending = null
        action?.invoke()
    }

    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "OBD диагностика")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Сподели отчета"))
    }

    private fun exportLog() {
        try {
            val dir = java.io.File(cacheDir, "logs").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // keep only the latest export
            val name = "odit-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt"
            val file = java.io.File(dir, name)
            file.writeText(vm.exportLog())
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Odit OBD лог")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Изпрати лога"))
        } catch (e: Exception) {
            vm.message = "Неуспешен експорт: ${e.message}"
        }
    }

    private fun searchWeb(query: String) {
        val url = "https://www.google.com/search?q=" + URLEncoder.encode("$query код грешка", "UTF-8")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            vm.message = "Няма инсталиран браузър."
        }
    }
}
