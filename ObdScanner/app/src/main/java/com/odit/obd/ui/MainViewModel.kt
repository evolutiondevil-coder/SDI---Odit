package com.odit.obd.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.odit.obd.bt.BleTransport
import com.odit.obd.bt.ClassicTransport
import com.odit.obd.bt.DeviceScanner
import com.odit.obd.bt.FoundDevice
import com.odit.obd.bt.ObdTransport
import com.odit.obd.bt.TransportException
import com.odit.obd.elm.CommLog
import com.odit.obd.elm.Elm327
import com.odit.obd.elm.ObdProtocol
import com.odit.obd.obd.Brand
import com.odit.obd.obd.EcuDtc
import com.odit.obd.obd.EcuIdentity
import com.odit.obd.obd.FreezeFrame
import com.odit.obd.obd.ObdSession
import com.odit.obd.obd.PidDef
import com.odit.obd.obd.Pids
import com.odit.obd.obd.Readiness
import com.odit.obd.obd.VehicleNotRespondingException
import com.odit.obd.obd.VinDecoder
import com.odit.obd.obd.VinInfo
import com.odit.obd.sim.DemoScenario
import com.odit.obd.sim.SimulatedElm
import com.odit.obd.vin.VinParser
import com.odit.obd.vin.VinReader
import com.odit.obd.vin.VinReport
import com.odit.obd.BuildConfig
import com.odit.obd.uds.FoundModule
import com.odit.obd.uds.ModuleScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { CONNECT, HOME, DTC, MODULES, LIVE, READINESS, FREEZE, INFO, TERMINAL }

enum class LinkMode(val title: String) { AUTO("Авто"), BLE("BLE"), CLASSIC("Класически") }

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val step: String) : ConnState
    data object Connected : ConnState
    data class Failed(val message: String) : ConnState
}

data class SavedDevice(val address: String, val name: String)

data class Overview(
    val vin: String? = null,
    val vinInfo: VinInfo? = null,
    val voltage: Double? = null,
    val readiness: Readiness? = null,
    /** Every VIN the car reported, and from where. */
    val vinReport: VinReport = VinReport.EMPTY,
    /** The VIN shown was typed in by the user because the car did not report one. */
    val vinManual: Boolean = false,
)

data class DtcUi(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val stored: List<EcuDtc> = emptyList(),
    val pending: List<EcuDtc> = emptyList(),
    val permanent: List<EcuDtc> = emptyList(),
    val error: String? = null,
) {
    val total: Int get() = stored.size + pending.size + permanent.size
}

data class ModulesUi(
    val scanning: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val modules: List<FoundModule> = emptyList(),
    val finished: Boolean = false,
    val error: String? = null,
)

data class LiveUi(
    val loading: Boolean = false,
    val supported: List<PidDef> = emptyList(),
    val pinned: List<Int> = emptyList(),
    val values: Map<Int, String> = emptyMap(),
    val error: String? = null,
)

data class Loadable<T>(val loading: Boolean = false, val data: T? = null, val error: String? = null)

data class InfoData(val vin: String?, val identities: List<EcuIdentity>)

@SuppressLint("MissingPermission")
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("odit", Context.MODE_PRIVATE)
    val log = CommLog()
    private val btAdapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var deviceScanner: DeviceScanner? = null

    var screen by mutableStateOf(Screen.CONNECT)
        private set
    var conn by mutableStateOf<ConnState>(ConnState.Idle)
        private set
    var devices by mutableStateOf<List<FoundDevice>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var linkMode by mutableStateOf(
        runCatching { LinkMode.valueOf(prefs.getString("linkMode", null) ?: "AUTO") }.getOrDefault(LinkMode.AUTO),
    )
        private set
    var lastDevice by mutableStateOf(
        prefs.getString("lastAddress", null)?.let { SavedDevice(it, prefs.getString("lastName", null) ?: it) },
    )
        private set
    var demo by mutableStateOf(false)
        private set

    var linkDescription by mutableStateOf("")
        private set
    var adapterVersion by mutableStateOf("")
        private set
    var protocol by mutableStateOf<ObdProtocol?>(null)
        private set
    var vehicleError by mutableStateOf<String?>(null)
        private set
    var overview by mutableStateOf(Overview())
        private set
    var dtc by mutableStateOf(DtcUi())
        private set
    var modules by mutableStateOf(ModulesUi())
        private set
    var live by mutableStateOf(LiveUi())
        private set
    var readiness by mutableStateOf(Loadable<Readiness>())
        private set
    var freeze by mutableStateOf(Loadable<FreezeFrame>())
        private set
    var info by mutableStateOf(Loadable<InfoData>())
        private set

    /** Text of the operation in progress, shown as a progress bar. */
    var busy by mutableStateOf<String?>(null)
        private set

    /** One-shot message for the snackbar. */
    var message by mutableStateOf<String?>(null)

    private var transport: ObdTransport? = null
    private var session: ObdSession? = null
    private var connectJob: Job? = null
    private var opJob: Job? = null
    private var liveJob: Job? = null

    val bluetoothAvailable: Boolean get() = btAdapter != null
    val vehicleReady: Boolean get() = session?.vehicleConnected == true
    private val brand: Brand get() = overview.vinInfo?.brand ?: Brand.OTHER

    // ------------------------------------------------------------- navigation

    fun open(target: Screen) {
        val needsVehicle = target !in setOf(Screen.CONNECT, Screen.HOME, Screen.TERMINAL, Screen.INFO)
        if (needsVehicle && !vehicleReady) {
            message = "Няма връзка с автомобила. Включете запалването и натиснете „Опитай отново“."
            return
        }
        if (screen == Screen.LIVE && target != Screen.LIVE) stopLive()
        screen = target
        when (target) {
            Screen.DTC -> if (!dtc.loaded && !dtc.loading) readDtcs()
            Screen.LIVE -> startLive()
            Screen.READINESS -> loadReadiness()
            Screen.FREEZE -> if (freeze.data == null) loadFreeze()
            Screen.INFO -> if (info.data == null) loadInfo()
            else -> Unit
        }
    }

    /** Returns false when the system should handle back (leave the app). */
    fun back(): Boolean = when (screen) {
        Screen.CONNECT, Screen.HOME -> false
        else -> {
            open(Screen.HOME)
            true
        }
    }

    // --------------------------------------------------------------- scanning

    fun chooseLinkMode(mode: LinkMode) {
        linkMode = mode
        prefs.edit().putString("linkMode", mode.name).apply()
    }

    fun startScan() {
        val adapter = btAdapter ?: return
        stopScan()
        val s = DeviceScanner(getApplication(), adapter)
        deviceScanner = s
        scanning = true
        devices = emptyList()
        s.start { list -> devices = list }
        viewModelScope.launch {
            delay(15_000)
            if (deviceScanner === s) stopScan()
        }
    }

    fun stopScan() {
        deviceScanner?.stop()
        deviceScanner = null
        scanning = false
    }

    // ------------------------------------------------------------- connection

    fun connect(address: String, name: String, seenBle: Boolean = false) {
        val adapter = btAdapter ?: return
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        stopScan()
        val order = transportOrder(device, seenBle)
        connectWith(name, order, SavedDevice(address, name)) { kind ->
            if (kind == LinkMode.BLE) BleTransport(getApplication(), device) else ClassicTransport(adapter, device)
        }
    }

    fun connectDemo(scenario: DemoScenario = DemoScenario.NORMAL) {
        stopScan()
        demoScenario = scenario
        connectWith("демо: ${scenario.title}", listOf(LinkMode.AUTO), null) { SimulatedElm(scenario) }
    }

    private var demoScenario: DemoScenario? = null

    private fun transportOrder(device: BluetoothDevice, seenBle: Boolean): List<LinkMode> {
        when (linkMode) {
            LinkMode.BLE -> return listOf(LinkMode.BLE)
            LinkMode.CLASSIC -> return listOf(LinkMode.CLASSIC)
            LinkMode.AUTO -> Unit
        }
        val remembered = prefs.getString("lastKind", null)
            ?.takeIf { prefs.getString("lastAddress", null) == device.address }
            ?.let { runCatching { LinkMode.valueOf(it) }.getOrNull() }
        val type = runCatching { device.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
        val order = when (type) {
            BluetoothDevice.DEVICE_TYPE_LE -> listOf(LinkMode.BLE)
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> listOf(LinkMode.CLASSIC)
            BluetoothDevice.DEVICE_TYPE_DUAL -> listOf(LinkMode.BLE, LinkMode.CLASSIC)
            else -> if (seenBle) listOf(LinkMode.BLE, LinkMode.CLASSIC) else listOf(LinkMode.CLASSIC, LinkMode.BLE)
        }
        return if (remembered != null && remembered in order) listOf(remembered) + (order - remembered) else order
    }

    private fun connectWith(
        label: String,
        order: List<LinkMode>,
        save: SavedDevice?,
        makeTransport: (LinkMode) -> ObdTransport,
    ) {
        disconnectInternal()
        connectJob = viewModelScope.launch {
            conn = ConnState.Connecting("Свързване с $label…")
            var lastError: Throwable? = null
            for (kind in order) {
                val t = makeTransport(kind)
                try {
                    conn = ConnState.Connecting("Свързване (${t.description})…")
                    withTimeout(45_000) { t.connect() }
                    conn = ConnState.Connecting("Инициализация на адаптера…")
                    val s = ObdSession(Elm327(t, log), log)
                    s.initAdapter()
                    transport = t
                    session = s
                    demo = t is SimulatedElm
                    linkDescription = t.description
                    adapterVersion = s.adapterVersion
                    save?.let { saveDevice(it, kind) }
                    conn = ConnState.Connected
                    screen = Screen.HOME
                    connectVehicle(s)
                    return@launch
                } catch (e: CancellationException) {
                    t.close()
                    throw e
                } catch (e: Exception) {
                    log.note("неуспех (${t.description}): ${e.message}")
                    t.close()
                    lastError = e
                }
            }
            conn = ConnState.Failed(
                (lastError?.message ?: "Неуспешна връзка") +
                    "\n\nПроверете дали адаптерът е включен в OBD буксата и дали светодиодът му свети. " +
                    "Затворете други OBD приложения (напр. MUCAR), защото адаптерът приема само една връзка.",
            )
        }
    }

    private fun saveDevice(d: SavedDevice, kind: LinkMode) {
        lastDevice = d
        prefs.edit()
            .putString("lastAddress", d.address)
            .putString("lastName", d.name)
            .putString("lastKind", kind.name)
            .apply()
    }

    private suspend fun connectVehicle(s: ObdSession) {
        vehicleError = null
        busy = "Свързване с автомобила…"
        try {
            val preferred = prefs.getString("protocol", null)?.firstOrNull()?.let { ObdProtocol.fromCode(it) }
            val p = s.connectVehicle(if (demo) null else preferred) { step -> busy = step }
            protocol = p
            if (!demo) prefs.edit().putString("protocol", p.code.toString()).apply()
            busy = "Четене на данни за автомобила…"
            loadOverview(s)
        } catch (e: VehicleNotRespondingException) {
            vehicleError = e.message
        } catch (e: TransportException) {
            connectionLost(e)
        } finally {
            busy = null
        }
    }

    fun retryVehicle() {
        val s = session ?: return
        if (opJob?.isActive == true) return
        opJob = viewModelScope.launch { connectVehicle(s) }
    }

    private suspend fun loadOverview(s: ObdSession) {
        val voltage = soft { s.batteryVoltage() }
        val report = soft { VinReader(s).read(modules.modules) } ?: VinReport.EMPTY
        val r = soft { s.readiness() }
        overview = overview.copy(voltage = voltage, readiness = r)
        applyVin(report)
    }

    /** Runs a read that may fail without stopping the caller; a lost connection still propagates. */
    private inline fun <T> soft(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TransportException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Key under which per-car data is stored before the garage knows the car. */
    private val carKey: String
        get() = if (demo) "demo:${demoScenario?.name}" else (lastDevice?.address ?: "unknown")

    private fun manualVin(): String? = prefs.getString("manualVin:$carKey", null)

    private fun applyVin(report: VinReport) {
        val manual = manualVin()
        val vin = report.vin ?: manual
        overview = overview.copy(
            vin = vin,
            vinInfo = vin?.let { VinDecoder.decode(it) },
            vinReport = report,
            vinManual = report.vin == null && manual != null,
        )
    }

    /** Returns an error text, or null when the VIN was accepted. */
    fun setManualVin(input: String): String? {
        val vin = input.trim().uppercase().replace(" ", "")
        if (vin.isEmpty()) {
            prefs.edit().remove("manualVin:$carKey").apply()
            applyVin(overview.vinReport)
            return null
        }
        if (!VinParser.isValidFormat(vin)) {
            return "VIN трябва да е точно 17 знака: цифри и латински букви без I, O и Q."
        }
        prefs.edit().putString("manualVin:$carKey", vin).apply()
        applyVin(overview.vinReport)
        return null
    }

    /** Text of the raw communication log for sharing. */
    fun exportLog(): String = log.export(
        linkedMapOf(
            "app" to "Odit OBD ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "exported" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            "adapter" to listOf(adapterVersion, linkDescription).filter { it.isNotBlank() }.joinToString(" · "),
            "protocol" to (protocol?.let { "${it.code} ${it.title}" } ?: "—"),
            "vin" to (overview.vin ?: "—"),
            "vin-sources" to overview.vinReport.found.joinToString { "${it.ecu}/${it.method.name}=${it.vin}" }.ifEmpty { "—" },
            "android" to "${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})",
        ),
    )

    fun refreshOverview() {
        op("Обновяване…") { s -> loadOverview(s) }
    }

    fun disconnect() {
        disconnectInternal()
        screen = Screen.CONNECT
        conn = ConnState.Idle
    }

    private fun disconnectInternal() {
        connectJob?.cancel()
        opJob?.cancel()
        liveJob?.cancel()
        connectJob = null
        opJob = null
        liveJob = null
        transport?.close()
        transport = null
        session = null
        demo = false
        busy = null
        protocol = null
        vehicleError = null
        overview = Overview()
        dtc = DtcUi()
        modules = ModulesUi()
        live = LiveUi()
        readiness = Loadable()
        freeze = Loadable()
        info = Loadable()
    }

    private fun connectionLost(e: Throwable) {
        log.note("връзката прекъсна: ${e.message}")
        disconnectInternal()
        screen = Screen.CONNECT
        conn = ConnState.Failed("Връзката с адаптера прекъсна. Свържете се отново.")
    }

    /** Runs one adapter operation at a time and turns errors into messages. */
    private fun op(label: String, onError: ((String) -> Unit)? = null, block: suspend (ObdSession) -> Unit): Boolean {
        val s = session ?: run { message = "Няма връзка с адаптера"; return false }
        if (opJob?.isActive == true) {
            message = "Изчакайте текущата операция да завърши"
            return false
        }
        opJob = viewModelScope.launch {
            busy = label
            try {
                block(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                connectionLost(e)
            } catch (e: Exception) {
                val text = e.message ?: e.javaClass.simpleName
                if (onError != null) onError(text) else message = text
            } finally {
                busy = null
            }
        }
        return true
    }

    // ------------------------------------------------------------------ DTCs

    fun readDtcs() {
        dtc = dtc.copy(loading = true, error = null)
        val started = op("Четене на грешките…", onError = { dtc = dtc.copy(loading = false, error = it) }) { s ->
            val stored = s.readDtcs(0x03)
            val pending = s.readDtcs(0x07)
            val permanent = runCatching { s.readDtcs(0x0A) }.getOrDefault(emptyList())
            dtc = DtcUi(loading = false, loaded = true, stored = stored, pending = pending, permanent = permanent)
        }
        if (!started) dtc = dtc.copy(loading = false)
    }

    fun clearDtcs() {
        op("Изтриване на грешките…") { s ->
            val result = s.clearDtcs()
            message = result.message
            delay(500)
            val stored = s.readDtcs(0x03)
            val pending = s.readDtcs(0x07)
            val permanent = runCatching { s.readDtcs(0x0A) }.getOrDefault(emptyList())
            dtc = DtcUi(loaded = true, stored = stored, pending = pending, permanent = permanent)
            freeze = Loadable()
            loadOverview(s)
        }
    }

    // --------------------------------------------------------------- modules

    fun scanModules(full: Boolean) {
        modules = ModulesUi(scanning = true)
        val started = op(if (full) "Пълно сканиране на модулите…" else "Сканиране на модулите…",
            onError = { modules = modules.copy(scanning = false, error = it) }) { s ->
            try {
                val found = ModuleScanner(s).scan(
                    full, brand,
                    onProgress = { done, total, label -> modules = modules.copy(done = done, total = total, current = label) },
                    onFound = { m -> modules = modules.copy(modules = modules.modules + m) },
                )
                modules = modules.copy(scanning = false, finished = true, modules = found)
                val before = overview.vinReport
                if (before.vin == null && s.protocol.isCan) {
                    // The VIN may only be stored in a module the scan just found.
                    applyVin(soft { VinReader(s).read(found) } ?: VinReader.withModules(before, found))
                } else {
                    applyVin(VinReader.withModules(before, found))
                }
            } finally {
                if (modules.scanning) modules = modules.copy(scanning = false, finished = true)
            }
        }
        if (!started) modules = ModulesUi()
    }

    fun stopModuleScan() {
        if (modules.scanning) opJob?.cancel()
    }

    fun clearModules(list: List<FoundModule>) {
        op("Изтриване на грешките в модулите…") { s ->
            val results = ModuleScanner(s).clear(list, brand)
            var current = modules.modules
            val failures = mutableListOf<String>()
            for ((i, pair) in results.withIndex()) {
                val (r, again) = pair
                val m = list[i]
                if (again != null) current = current.map { if (it.tx == m.tx) again else it }
                if (!r.ok) failures += "${m.title}: ${r.message}"
            }
            modules = modules.copy(modules = current)
            message = if (failures.isEmpty()) "Грешките са изтрити." else failures.joinToString("\n")
        }
    }

    // ------------------------------------------------------------- live data

    private fun startLive() {
        val s = session ?: return
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            if (live.supported.isEmpty()) {
                live = live.copy(loading = true, error = null)
                while (opJob?.isActive == true) delay(200)
                try {
                    val defs = s.supportedPids(1).filter { !Pids.isSupportMask(it) }.mapNotNull { Pids.all[it] }
                    val saved = prefs.getString("pinned", null)
                        ?.split(',')?.mapNotNull { it.toIntOrNull(16) }
                        ?.filter { p -> defs.any { it.pid == p } }
                    val pinned = saved?.takeIf { it.isNotEmpty() }
                        ?: Pids.defaultPinned.filter { p -> defs.any { it.pid == p } }.take(8)
                    live = LiveUi(supported = defs, pinned = pinned)
                    if (defs.isEmpty()) live = live.copy(error = "Автомобилът не съобщава поддържани параметри.")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TransportException) {
                    connectionLost(e)
                    return@launch
                } catch (e: Exception) {
                    live = live.copy(loading = false, error = e.message)
                    return@launch
                }
            }
            while (isActive) {
                val pins = live.pinned
                if (pins.isEmpty() || opJob?.isActive == true) {
                    delay(300)
                    continue
                }
                for (pid in pins) {
                    if (!isActive) break
                    try {
                        val def = Pids.all[pid] ?: continue
                        val raw = s.readPidRaw(pid)
                        live = live.copy(values = live.values + (pid to (raw?.let { def.formatRaw(it) } ?: "—")))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: TransportException) {
                        connectionLost(e)
                        return@launch
                    } catch (_: Exception) {
                        // A single failed read is not fatal; keep polling.
                    }
                }
                delay(50)
            }
        }
    }

    private fun stopLive() {
        liveJob?.cancel()
        liveJob = null
    }

    fun togglePin(pid: Int) {
        val list = if (pid in live.pinned) live.pinned - pid else live.pinned + pid
        live = live.copy(pinned = list)
        prefs.edit().putString("pinned", list.joinToString(",") { "%02X".format(it) }).apply()
    }

    // --------------------------------------------------------- other screens

    fun loadReadiness() {
        readiness = readiness.copy(loading = true, error = null)
        if (!op("Четене на мониторите…", onError = { readiness = Loadable(error = it) }) { s ->
            val r = s.readiness()
            readiness = if (r == null) Loadable(error = "Автомобилът не върна данни за готовност.") else Loadable(data = r)
            if (r != null) overview = overview.copy(readiness = r)
        }) readiness = readiness.copy(loading = false)
    }

    fun loadFreeze() {
        freeze = freeze.copy(loading = true, error = null)
        if (!op("Четене на замразените данни…", onError = { freeze = Loadable(error = it) }) { s ->
            val ff = s.freezeFrame()
            freeze = if (ff == null) {
                Loadable(error = "Няма замразени данни (обикновено се записват само когато има запаметена грешка).")
            } else {
                Loadable(data = ff)
            }
        }) freeze = freeze.copy(loading = false)
    }

    fun loadInfo() {
        info = info.copy(loading = true, error = null)
        if (!op("Четене на информацията…", onError = { info = Loadable(error = it) }) { s ->
            applyVin(VinReader(s).read(modules.modules))
            val voltage = s.batteryVoltage()
            if (voltage != null) overview = overview.copy(voltage = voltage)
            info = Loadable(data = InfoData(overview.vin, s.identities()))
        }) info = info.copy(loading = false)
    }

    // ---------------------------------------------------------------- terminal

    fun sendRaw(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        op("Изпращане…") { s ->
            s.elm.send(cmd, 10_000)
            if (cmd.startsWith("AT", ignoreCase = true)) s.adapterDirty = true
        }
    }

    // ------------------------------------------------------------------ report

    fun buildReport(): String = buildString {
        val now = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date())
        appendLine("Odit OBD — диагностичен отчет ($now)")
        overview.vinInfo?.let { v ->
            appendLine("Автомобил: ${listOfNotNull(v.make, v.year?.toString()).joinToString(", ").ifEmpty { "—" }}")
        }
        overview.vin?.let { appendLine("VIN: $it") }
        protocol?.let { appendLine("Протокол: ${it.title}") }
        overview.voltage?.let { appendLine("Напрежение: %.1f V".format(Locale.US, it)) }
        overview.readiness?.let {
            appendLine("Лампа на двигателя: ${if (it.milOn) "СВЕТИ" else "не свети"} (${it.dtcCount} запаметени)")
        }
        if (dtc.loaded) {
            appendLine()
            appendLine("ГРЕШКИ НА ДВИГАТЕЛЯ (OBD)")
            if (dtc.total == 0) appendLine("  няма")
            fun section(title: String, list: List<EcuDtc>) {
                if (list.isEmpty()) return
                appendLine("  $title:")
                for (d in list) appendLine("    ${d.dtc.code} [${ObdSession.ecuName(d.ecu)}] — ${d.dtc.description}")
            }
            section("Запаметени", dtc.stored)
            section("Изчакващи", dtc.pending)
            section("Постоянни", dtc.permanent)
        }
        if (modules.modules.isNotEmpty()) {
            appendLine()
            appendLine("МОДУЛИ")
            for (m in modules.modules) {
                val ident = m.identification?.let { " ($it)" }.orEmpty()
                appendLine("  ${m.title} ${m.addressLabel}$ident")
                m.readError?.let { appendLine("    ! $it") }
                if (m.dtcs.isEmpty() && m.readError == null) appendLine("    няма грешки")
                for (d in m.dtcs) {
                    val ftb = d.ftbDescription?.let { " ($it)" }.orEmpty()
                    appendLine("    ${d.displayCode} [${d.kind.title}] — ${d.description}$ftb")
                }
            }
        }
    }

    override fun onCleared() {
        stopScan()
        disconnectInternal()
        super.onCleared()
    }
}
