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
import com.odit.obd.sim.DemoScenario
import com.odit.obd.sim.SimulatedElm
import com.odit.obd.vin.VinParser
import com.odit.obd.vin.VinReader
import com.odit.obd.vin.VinReport
import com.odit.obd.BuildConfig
import com.odit.obd.uds.DiagType
import com.odit.obd.uds.FoundModule
import com.odit.obd.uds.KLineDirect
import com.odit.obd.uds.ModuleCatalog
import com.odit.obd.obd.AdapterCheck
import com.odit.obd.obd.AdapterReport
import com.odit.obd.obd.ProtocolAttempt
import com.odit.obd.uds.ModuleAddress
import com.odit.obd.data.AppPackSource
import com.odit.obd.data.DataPacks
import com.odit.obd.data.PackUpdater
import com.odit.obd.obd.DtcDatabase
import com.odit.obd.data.PackInfo
import com.odit.obd.garage.CarRecord
import com.odit.obd.garage.Garage
import com.odit.obd.garage.SavedModule
import com.odit.obd.garage.ScanRecord
import com.odit.obd.vehicle.UserVehicle
import com.odit.obd.vehicle.VehicleDecoder
import com.odit.obd.vehicle.VehicleIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.odit.obd.net.HttpClient
import com.odit.obd.net.Network
import com.odit.obd.online.DemoOnlineDecoder
import com.odit.obd.online.OnlineVehicleDecoder
import com.odit.obd.online.VpicDecoder
import kotlinx.coroutines.withContext
import java.io.File
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

enum class Screen { CONNECT, HOME, DTC, MODULES, LIVE, READINESS, FREEZE, INFO, TERMINAL, GARAGE }

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
    val voltage: Double? = null,
    val readiness: Readiness? = null,
    /** Every VIN the car reported, and from where. */
    val vinReport: VinReport = VinReport.EMPTY,
    /** The VIN shown was typed in by the user because the car did not report one. */
    val vinManual: Boolean = false,
)

/** Outcome of the last clear, shown on the screen until the next read. */
data class ClearNotice(val ok: Boolean, val title: String, val detail: String? = null)

/** "Грешката е изтрита." / "Грешките са изтрити (3)." */
fun clearedText(n: Int): String = when (n) {
    0 -> "Няма грешки за изтриване."
    1 -> "Грешката е изтрита."
    else -> "Грешките са изтрити ($n)."
}

data class DtcUi(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val stored: List<EcuDtc> = emptyList(),
    val pending: List<EcuDtc> = emptyList(),
    val permanent: List<EcuDtc> = emptyList(),
    val error: String? = null,
    /** Codes that were cleared but the car reported again at once (the fault is still present). */
    val returned: Set<String> = emptySet(),
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
    /** "tx:code" of codes that came back right after clearing. */
    val returned: Set<String> = emptySet(),
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

    /** Why the car probably did not answer, in plain words (shown with [vehicleError]). */
    var connectDiagnosis by mutableStateOf<String?>(null)
        private set

    /** What the adapter answered for every protocol tried. */
    var connectAttempts by mutableStateOf<List<ProtocolAttempt>>(emptyList())
        private set

    /** Result of the last "Проверка на адаптера". */
    var adapterReport by mutableStateOf<AdapterReport?>(null)
        private set

    /** VINs typed earlier for cars used with this adapter (offered as one-tap choices). */
    var vinSuggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var overview by mutableStateOf(Overview())
        private set
    var dtc by mutableStateOf(DtcUi())
        private set

    /** What the last clear did; cleared again by the next read or scan. */
    var clearNotice by mutableStateOf<ClearNotice?>(null)
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

    // ------------------------------------------------------------ car data
    private val packs = AppPackSource(app)
    @Volatile private var data: DataPacks = DataPacks.EMPTY
    private val decoder: VehicleDecoder get() = VehicleDecoder(data.wmi, data.vinRules)
    private val realGarage = Garage(File(app.filesDir, "garage"))
    private val demoGarage = Garage(File(app.cacheDir, "demo-garage"))
    private val garage: Garage get() = if (demo) demoGarage else realGarage

    /** Make, model, year… of the connected car, with how sure each value is. */
    var vehicle by mutableStateOf(VehicleIdentity.UNKNOWN)
        private set

    /** The garage entry of the connected car. */
    var car by mutableStateOf<CarRecord?>(null)
        private set

    /** All cars in the garage (real ones, not demo cars). */
    var garageCars by mutableStateOf<List<CarRecord>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            reloadPacks()
            refreshGarage()
            maybeUpdatePacks()
        }
    }

    private suspend fun reloadPacks() {
        data = withContext(Dispatchers.IO) { runCatching { DataPacks.load(packs) }.getOrDefault(DataPacks.EMPTY) }
        recomputeVehicle()
    }

    /** When the data packs were last checked for updates (0 = never). */
    var packsCheckedAt by mutableStateOf(prefs.getLong("packsCheckedAt", 0L))
        private set
    var packsUpdating by mutableStateOf(false)
        private set
    private var packCheckForMake: String? = null

    /**
     * Downloads newer data packs (at most once a day, or when a make is recognised that may have its
     * own pack). Runs on the network scope; the app keeps using the current packs meanwhile.
     */
    fun maybeUpdatePacks(force: Boolean = false) {
        if (!onlineEnabled || demo || packsUpdating) return
        val make = vehicle.make?.value?.lowercase()
        val due = force || System.currentTimeMillis() - packsCheckedAt > 24 * 3600_000L || (make != null && make != packCheckForMake)
        if (!due) return
        packCheckForMake = make
        val app = getApplication<Application>()
        packsUpdating = true
        onlineScope.launch {
            val result = if (Network.isOnline(app)) {
                PackUpdater(http, File(app.filesDir, "odit-data")).update(
                    localVersion = { packs.version(it) },
                    wanted = { e -> e.makes.isEmpty() || (make != null && make in e.makes) || e.id in packs.ids() },
                )
            } else {
                null
            }
            withContext(Dispatchers.Main) {
                packsUpdating = false
                if (result?.reachable == true) {
                    packsCheckedAt = System.currentTimeMillis()
                    prefs.edit().putLong("packsCheckedAt", packsCheckedAt).apply()
                    if (result.updated.isNotEmpty()) {
                        reloadPacks()
                        message = "Обновени данни: ${result.updated.joinToString()}"
                    } else if (force) {
                        message = "Данните са актуални."
                    }
                } else if (force) {
                    message = "Няма връзка със сървъра за данни."
                }
            }
        }
    }

    // ------------------------------------------------------------ online help
    private val http = HttpClient()

    /** Network work runs here, never in the adapter queue ([op]), so it cannot slow a scan down. */
    private val onlineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** User consent for "Онлайн помощ" (off until the user agrees). */
    var onlineEnabled by mutableStateOf(prefs.getBoolean("online", false))
        private set

    /** The first-run explanation has been answered. */
    var onlineAsked by mutableStateOf(prefs.getBoolean("onlineAsked", false))
        private set

    /** An online lookup is running (shown as a small note, never blocks anything). */
    var onlineBusy by mutableStateOf(false)
        private set

    /** Demo only: simulate having internet. */
    var demoOnline by mutableStateOf(true)

    private val triedOnline = mutableSetOf<String>()

    fun setOnline(enabled: Boolean) {
        onlineEnabled = enabled
        onlineAsked = true
        prefs.edit().putBoolean("online", enabled).putBoolean("onlineAsked", true).apply()
        if (enabled) {
            car?.let { maybeDecodeOnline(it) }
            maybeUpdatePacks()
        }
    }

    private val vehicleProvider: OnlineVehicleDecoder
        get() = if (demo) DemoOnlineDecoder(demoOnline) else VpicDecoder(http)

    /** What "Онлайн помощ" sends, for the explanation texts. */
    val onlineSends: String get() = VpicDecoder(http).sends

    /**
     * Adds what the online decoder knows about the car, in the background, once per car (cached in the
     * garage so it is there offline next time). Silent when offline or when the service has nothing.
     */
    private fun maybeDecodeOnline(c: CarRecord) {
        val vin = c.vin ?: return
        if (c.online != null) return
        val allowed = if (demo) true else onlineEnabled
        if (!allowed || !triedOnline.add(c.id)) return
        val provider = vehicleProvider
        val app = getApplication<Application>()
        onlineScope.launch {
            if (!demo && !Network.isOnline(app)) {
                triedOnline.remove(c.id) // try again later, when there is a connection
                return@launch
            }
            withContext(Dispatchers.Main) { onlineBusy = true }
            val info = try {
                provider.decode(vin)
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                onlineBusy = false
                if (info != null && car?.id == c.id) {
                    updateCar { it.copy(online = info) }
                    recomputeVehicle()
                }
            }
        }
    }

    /** Titles of the bundled / downloaded data packs and their sources (shown in "Информация"). */
    fun dataSources(): List<PackInfo> = data.infos

    private var transport: ObdTransport? = null
    private var session: ObdSession? = null
    private var connectJob: Job? = null
    private var opJob: Job? = null
    private var liveJob: Job? = null

    val bluetoothAvailable: Boolean get() = btAdapter != null
    val vehicleReady: Boolean get() = session?.vehicleConnected == true

    /** The adapter is connected (even if the car did not answer standard OBD). */
    val adapterReady: Boolean get() = session != null
    private val brand: Brand get() = vehicle.group

    // ------------------------------------------------------------- navigation

    fun open(target: Screen) {
        val needsVehicle = target !in setOf(Screen.CONNECT, Screen.HOME, Screen.TERMINAL, Screen.INFO, Screen.GARAGE)
        // Modules can also be reached directly over the K-line when standard OBD got no answer.
        val allowed = vehicleReady || (target == Screen.MODULES && adapterReady)
        if (needsVehicle && !allowed) {
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
            Screen.GARAGE -> refreshGarage()
            else -> Unit
        }
    }

    /** The garage is reachable both before and after connecting. */
    fun closeGarage() {
        screen = if (session != null) Screen.HOME else Screen.CONNECT
    }

    /** Returns false when the system should handle back (leave the app). */
    fun back(): Boolean = when (screen) {
        Screen.CONNECT, Screen.HOME -> false
        Screen.GARAGE -> {
            closeGarage()
            true
        }
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
        demoGarage.all().forEach { demoGarage.delete(it.id) }
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
                    val s = ObdSession(Elm327(t, log), log) { android.os.SystemClock.elapsedRealtime() }
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
        connectDiagnosis = null
        busy = "Свързване с автомобила…"
        try {
            // Tried first, for a faster reconnect: the protocol saved for this VIN (if the user gave one),
            // else that of the car last seen with this adapter.
            val fromGarage = car?.protocol ?: lastDevice?.address?.takeIf { !demo }?.let { garage.lastForAdapter(it)?.protocol }
            val preferred = (fromGarage ?: prefs.getString("protocol", null)?.firstOrNull())?.let { ObdProtocol.fromCode(it) }
            val p = s.connectVehicle(if (demo) null else preferred) { step -> busy = step }
            protocol = p
            connectAttempts = s.attempts
            if (!demo) prefs.edit().putString("protocol", p.code.toString()).apply()
            busy = "Четене на данни за автомобила…"
            loadOverview(s)
        } catch (e: VehicleNotRespondingException) {
            connectAttempts = s.attempts
            val voltage = runCatching { s.batteryVoltage() }.getOrNull()
            overview = overview.copy(voltage = voltage)
            connectDiagnosis = diagnose(s.attempts, voltage)
            vehicleError = e.message
            refreshVinSuggestions()
        } catch (e: TransportException) {
            connectionLost(e)
        } finally {
            busy = null
        }
    }

    /** Plain-words guess at why no protocol worked, from how the adapter answered. */
    private fun diagnose(attempts: List<ProtocolAttempt>, voltage: Double?): String {
        val kLine = attempts.filter { it.protocol in setOf(ObdProtocol.KWP_FAST, ObdProtocol.KWP_SLOW, ObdProtocol.ISO_9141) }
        val volts = voltage?.let { "%.1f V".format(Locale.US, it) }
        return when {
            voltage != null && voltage < 6.0 ->
                "Адаптерът не отчита напрежение от колата ($volts). Проверете дали е влязъл добре в буксата и бушона " +
                    "на диагностичната букса (често е общ със запалката)."
            // A real K-line wake-up takes seconds; an instant refusal means the adapter never tried.
            kLine.isNotEmpty() && kLine.all { it.answer == "?" || (it.ms < 900 && it.answer != "OK") } ->
                "Адаптерът отказа опитите по K-line почти мигновено — изглежда в този режим той не поддържа K-line, " +
                    "а по нея говорят колите отпреди CAN (до около 2005–2008 г.). Пратете лога, за да се потвърди."
            else ->
                "Колата не отговори на нито един стандартен OBD2 протокол" + (volts?.let { " (напрежение $it)" } ?: "") + ". " +
                    "Ако запалването е включено, най-вероятно колата е отпреди OBD2: в Европа той е задължителен от 2001 г. " +
                    "за бензиновите и от 2004 г. за дизеловите коли. Опитайте „Директна връзка“ по-долу."
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

    /** VIN typed for the car connected now. It is not kept for the adapter: the next car may be another one. */
    private var sessionManualVin: String? = null

    private fun manualVin(): String? = sessionManualVin

    private fun refreshVinSuggestions() {
        val adapter = if (demo) "demo" else lastDevice?.address
        val fromGarage = runCatching { garage.all() }.getOrDefault(emptyList())
            .filter { it.vinManual && it.vin != null && it.adapter == adapter }.mapNotNull { it.vin }
        // Version 1.1 kept one typed VIN per adapter.
        val legacy = prefs.getString("manualVin:$carKey", null)
        vinSuggestions = (fromGarage + listOfNotNull(legacy)).distinct().filter { it != overview.vin }.take(3)
    }

    private fun applyVin(report: VinReport) {
        val manual = manualVin()
        val vin = report.vin ?: manual
        overview = overview.copy(
            vin = vin,
            vinReport = report,
            vinManual = report.vin == null && manual != null,
        )
        attachCar(vin)
        if (report.vin == null && manual != null) updateCar { it.copy(vinManual = true) }
        refreshVinSuggestions()
    }

    /** Finds or creates the garage entry for the connected car and refreshes what is known about it. */
    private fun attachCar(vin: String?) {
        val adapter = if (demo) "demo" else lastDevice?.address
        val id = vin?.let { CarRecord.idForVin(it) } ?: adapter?.let { CarRecord.idForAdapter(it) } ?: return
        val existing = garage.get(id)
        val record = (existing ?: CarRecord(id, vin, if (vin == null) "Автомобил без VIN" else null, adapter)).copy(
            adapter = adapter,
            protocol = protocol?.code ?: existing?.protocol,
            lastSeen = System.currentTimeMillis(),
        )
        runCatching { garage.save(record) }
        car = record
        recomputeVehicle()
        refreshGarage()
        maybeDecodeOnline(record)
        maybeUpdatePacks()
    }

    private fun recomputeVehicle() {
        vehicle = if (overview.vin == null && car == null) VehicleIdentity.UNKNOWN else decoder.decode(overview.vin, car?.user, car?.online)
        // Selects the manufacturer pack for trouble-code descriptions.
        DtcDatabase.currentMake = vehicle.make?.value
    }

    private fun updateCar(change: (CarRecord) -> CarRecord) {
        val c = car ?: return
        val updated = change(c)
        runCatching { garage.save(updated) }
        car = updated
        refreshGarage()
    }

    private fun addHistory(kind: String, codes: List<String>, note: String? = null) =
        updateCar { it.copy(history = (it.history + ScanRecord(System.currentTimeMillis(), kind, codes, note)).takeLast(Garage.MAX_HISTORY)) }

    fun refreshGarage() {
        garageCars = runCatching { realGarage.all() }.getOrDefault(emptyList())
    }

    /** Saves the user's corrections (empty fields clear a correction). */
    fun saveUserVehicle(user: UserVehicle) {
        updateCar { it.copy(user = user) }
        recomputeVehicle()
    }

    fun deleteCar(id: String) {
        runCatching { realGarage.delete(id) }
        refreshGarage()
    }

    /** Identity of a garage car without connecting (for the garage screen). */
    fun identityOf(c: CarRecord): VehicleIdentity = decoder.decode(c.vin, c.user, c.online)

    /** Returns an error text, or null when the VIN was accepted. */
    fun setManualVin(input: String): String? {
        val vin = input.trim().uppercase().replace(" ", "")
        if (vin.isEmpty()) {
            sessionManualVin = null
            applyVin(overview.vinReport)
            return null
        }
        if (!VinParser.isValidFormat(vin)) {
            return "VIN трябва да е точно 17 знака: цифри и латински букви без I, O и Q."
        }
        sessionManualVin = vin
        applyVin(overview.vinReport)
        return null
    }

    /** Checks which commands the adapter knows and whether its K-line is real (see [AdapterCheck]). */
    fun runAdapterCheck() {
        op("Проверка на адаптера…") { s ->
            stopLive()
            adapterReport = AdapterCheck(s) { android.os.SystemClock.elapsedRealtime() }.run { step -> busy = step }
        }
    }

    /** Text of the raw communication log for sharing. */
    fun exportLog(): String = log.export(
        linkedMapOf(
            "app" to "Odit OBD ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "exported" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            "adapter" to listOf(adapterVersion, linkDescription).filter { it.isNotBlank() }.joinToString(" · "),
            "protocol" to (protocol?.let { "${it.code} ${it.title}" } ?: "—"),
            "vin" to (overview.vin ?: "—"),
            "vehicle" to vehicle.shortName.ifBlank { "—" },
            "vin-sources" to overview.vinReport.found.joinToString { "${it.ecu}/${it.method.name}=${it.vin}" }.ifEmpty { "—" },
            "android" to "${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})",
            "adapter-check" to (adapterReport?.summary ?: "—"),
            "connect-attempts" to connectAttempts.joinToString("; ") { "${it.protocol.code}=${it.answer} (${it.ms} ms)" }.ifEmpty { "—" },
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
        connectDiagnosis = null
        connectAttempts = emptyList()
        adapterReport = null
        sessionManualVin = null
        vinSuggestions = emptyList()
        overview = Overview()
        vehicle = VehicleIdentity.UNKNOWN
        car = null
        onlineBusy = false
        triedOnline.clear()
        dtc = DtcUi()
        clearNotice = null
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
        clearNotice = null
        dtc = dtc.copy(loading = true, error = null)
        val started = op("Четене на грешките…", onError = { dtc = dtc.copy(loading = false, error = it) }) { s ->
            val stored = s.readDtcs(0x03)
            val pending = s.readDtcs(0x07)
            val permanent = runCatching { s.readDtcs(0x0A) }.getOrDefault(emptyList())
            dtc = DtcUi(loading = false, loaded = true, stored = stored, pending = pending, permanent = permanent)
            addHistory(ScanRecord.OBD, (stored + pending).map { it.dtc.code }.distinct())
        }
        if (!started) dtc = dtc.copy(loading = false)
    }

    fun clearDtcs() {
        op("Изтриване на грешките…") { s ->
            // Compare with what the car reports right now, not with a list that may be old.
            val before = (soft { s.readDtcs(0x03) + s.readDtcs(0x07) } ?: (dtc.stored + dtc.pending)).map { it.dtc.code }.distinct()
            val result = s.clearDtcs()
            delay(700) // the control unit needs a moment after clearing
            val stored = s.readDtcs(0x03)
            val pending = s.readDtcs(0x07)
            val permanent = runCatching { s.readDtcs(0x0A) }.getOrDefault(emptyList())
            val after = (stored + pending).map { it.dtc.code }.toSet()
            val back = before.filter { it in after }
            val gone = before.size - back.size
            dtc = DtcUi(loaded = true, stored = stored, pending = pending, permanent = permanent, returned = if (result.ok) back.toSet() else emptySet())
            freeze = Loadable()

            val permanentNote = if (permanent.isNotEmpty()) {
                "Постоянните грешки (${permanent.size}) не се изтриват с уред — изчезват сами, след като колата провери, че проблемът го няма."
            } else {
                null
            }
            val notice = when {
                !result.ok -> ClearNotice(false, "Грешките не са изтрити", result.message)
                back.isEmpty() -> ClearNotice(true, clearedText(gone), permanentNote)
                else -> ClearNotice(
                    gone > 0,
                    if (gone > 0) "Изтрити: $gone. Върнаха се веднага: ${back.size}" else if (back.size == 1) "Грешката се върна веднага" else "Грешките се върнаха веднага (${back.size})",
                    listOfNotNull(
                        "${back.joinToString()} — проблемът е налице в момента, затова колата записва грешката отново. Отстранете причината и изтрийте пак.",
                        permanentNote,
                    ).joinToString("\n"),
                )
            }
            clearNotice = notice
            message = notice.title
            if (result.ok && gone > 0) addHistory(ScanRecord.CLEAR, before.filter { it !in after }, "OBD")

            // The module list (if scanned) shows the same control units: refresh the ones that had codes.
            val stale = modules.modules.filter { it.dtcs.isNotEmpty() && it.type != DiagType.KLINE }
            if (result.ok && stale.isNotEmpty() && s.protocol.isCan) {
                busy = "Проверка на модулите…"
                val fresh = soft {
                    ModuleScanner(s).scan(false, brand, { _, _, _ -> }, {}, only = stale.map { ModuleAddress(it.tx, it.rx, it.catalogName) })
                }
                if (fresh != null) {
                    modules = modules.copy(
                        modules = modules.modules.map { old -> fresh.firstOrNull { it.tx == old.tx } ?: old },
                        returned = emptySet(),
                    )
                }
            }
            loadOverview(s)
            // Cleared from the home screen: show the result where the list is.
            if (screen == Screen.HOME) screen = Screen.DTC
        }
    }

    // --------------------------------------------------------------- modules

    /** Modules this car had last time (from the garage). */
    val knownModules: List<SavedModule> get() = car?.modules.orEmpty()

    fun scanModules(full: Boolean, knownOnly: Boolean = false) {
        clearNotice = null
        modules = ModulesUi(scanning = true)
        val only = if (knownOnly) knownModules.map { ModuleAddress(it.tx, it.rx, it.name) } else null
        val started = op(if (full) "Пълно сканиране на модулите…" else "Сканиране на модулите…",
            onError = { modules = modules.copy(scanning = false, error = it) }) { s ->
            try {
                val found = ModuleScanner(s).scan(
                    full, brand, only = only,
                    onProgress = { done, total, label -> modules = modules.copy(done = done, total = total, current = label) },
                    onFound = { m -> modules = modules.copy(modules = modules.modules + m) },
                )
                modules = modules.copy(scanning = false, finished = true, modules = found)
                updateCar { it.copy(modules = Garage.fromModules(found)) }
                addHistory(ScanRecord.MODULES, found.flatMap { m -> m.dtcs.map { it.displayCode } }.distinct(), "${found.size} модула")
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

    /** EXPERIMENTAL: looks for control units directly on the K-line (old cars, see [KLineDirect]). */
    fun scanKLine() {
        clearNotice = null
        modules = ModulesUi(scanning = true)
        val started = op("Търсене на модули по K-line…", onError = { modules = modules.copy(scanning = false, error = it) }) { s ->
            try {
                val found = KLineDirect(s).scan(
                    ModuleCatalog.kLineTargets(brand),
                    onProgress = { done, total, label -> modules = modules.copy(done = done, total = total, current = label) },
                    onFound = { m -> modules = modules.copy(modules = modules.modules + m) },
                )
                modules = modules.copy(
                    scanning = false, finished = true, modules = found,
                    error = if (found.isEmpty()) {
                        "Нито един модул не отговори по K-line. Проверете дали запалването е включено. Ако е — пратете лога " +
                            "(„Информация“ → „Експорт на лога“), за да се види какво отговаря адаптерът."
                    } else {
                        null
                    },
                )
                if (found.isNotEmpty()) {
                    updateCar { it.copy(modules = Garage.fromModules(found)) }
                    addHistory(ScanRecord.MODULES, found.flatMap { m -> m.dtcs.map { it.displayCode } }.distinct(), "${found.size} модула, K-line")
                    applyVin(VinReader.withModules(overview.vinReport, found))
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
            val (kLine, can) = list.partition { it.type == DiagType.KLINE }
            val list = can + kLine
            val results = (if (can.isEmpty()) emptyList() else ModuleScanner(s).clear(can, brand)) +
                (if (kLine.isEmpty()) emptyList() else KLineDirect(s).clear(kLine))
            var current = modules.modules
            var cleared = 0
            val clearedCodes = mutableListOf<String>()
            val back = mutableListOf<String>()
            val returned = mutableSetOf<String>()
            val failures = mutableListOf<String>()
            for ((i, pair) in results.withIndex()) {
                val (r, again) = pair
                val m = list[i]
                val beforeCodes = m.dtcs.map { it.displayCode }
                val now = when {
                    again != null -> again
                    // Cleared, but the module is still restarting: do not leave the old rows on screen.
                    r.ok -> m.copy(dtcs = emptyList(), readError = "Изтрито. Модулът не отговори при повторната проверка — сканирайте отново след малко.")
                    else -> m
                }
                current = current.map { if (it.tx == m.tx) now else it }
                if (r.ok) {
                    val still = now.dtcs.map { it.displayCode }.filter { it in beforeCodes }
                    cleared += beforeCodes.size - still.size
                    clearedCodes += beforeCodes.filter { it !in still }
                    still.forEach { returned += "${m.tx}:$it"; back += "${m.title}: $it" }
                } else {
                    failures += "${m.title}: ${r.message}"
                }
            }
            modules = modules.copy(modules = current, returned = returned)
            if (clearedCodes.isNotEmpty()) addHistory(ScanRecord.CLEAR, clearedCodes, "модули")

            val details = mutableListOf<String>()
            if (back.isNotEmpty()) {
                details += "Върнаха се веднага (проблемът е налице в момента): " + back.joinToString("; ")
            }
            if (failures.isNotEmpty()) details += failures
            val notice = when {
                details.isEmpty() -> ClearNotice(true, clearedText(cleared))
                cleared > 0 -> ClearNotice(true, "Изтрити: $cleared", details.joinToString("\n"))
                failures.isNotEmpty() && back.isEmpty() -> ClearNotice(false, "Грешките не са изтрити", details.joinToString("\n"))
                else -> ClearNotice(false, if (back.size == 1) "Грешката се върна веднага" else "Грешките се върнаха веднага (${back.size})", details.joinToString("\n"))
            }
            clearNotice = notice
            message = notice.title

            // The engine list (OBD) and the lamp status are now out of date too.
            dtc = DtcUi()
            freeze = Loadable()
            if (s.vehicleConnected) loadOverview(s)
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
        vehicle.shortName.ifBlank { null }?.let { appendLine("Автомобил: $it") }
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
        onlineScope.cancel()
        stopScan()
        disconnectInternal()
        super.onCleared()
    }
}
