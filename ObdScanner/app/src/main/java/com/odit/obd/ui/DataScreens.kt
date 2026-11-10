package com.odit.obd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.TextButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LiveScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val l = vm.live
    Page(title = "Данни на живо", busy = vm.busy, snackbar = snackbar, onBack = { vm.open(Screen.HOME) }) { padding ->
        if (l.loading) {
            Column(Modifier.padding(padding)) { CenteredLoading("Проверка кои параметри поддържа автомобилът…") }
            return@Page
        }
        val pinned = l.pinned.mapNotNull { pid -> l.supported.firstOrNull { it.pid == pid } }
        val others = l.supported.filter { it.pid !in l.pinned }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            l.error?.let { err -> item { InfoCard(color = Palette.Bad.copy(alpha = 0.15f)) { Text(err, color = Palette.Bad) } } }
            items(pinned.chunked(2), key = { row -> row.joinToString { it.pid.toString() } }) { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (def in row) {
                        InfoCard(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    def.name,
                                    color = Palette.Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.Filled.Close, contentDescription = "Премахни",
                                    tint = Palette.Muted,
                                    modifier = Modifier.clickable { vm.togglePin(def.pid) },
                                )
                            }
                            Text(
                                l.values[def.pid] ?: "…",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (others.isNotEmpty()) {
                item {
                    SectionTitle("Други параметри")
                    Text("Добавете с +. Колкото по-малко са показаните, толкова по-бързо се обновяват.",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                }
                items(others, key = { "o" + it.pid }) { def ->
                    InfoCard(modifier = Modifier.clickable { vm.togglePin(def.pid) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(def.name, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.Add, contentDescription = "Добави", tint = Palette.Primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReadinessScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val st = vm.readiness
    Page(
        title = "Готовност (монитори)",
        busy = vm.busy,
        snackbar = snackbar,
        onBack = { vm.open(Screen.HOME) },
        actions = { IconButton(onClick = { vm.loadReadiness() }, enabled = vm.busy == null) { Icon(Icons.Filled.Refresh, "Обнови") } },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            st.error?.let { InfoCard(color = Palette.Bad.copy(alpha = 0.15f)) { Text(it, color = Palette.Bad) } }
            val r = st.data ?: return@Column
            InfoCard {
                if (r.milOn) Chip("Лампата на двигателя СВЕТИ", Palette.Bad) else Chip("Лампата на двигателя не свети", Palette.Ok)
                KeyValue("Запаметени грешки", r.dtcCount.toString())
                KeyValue("Двигател", if (r.diesel) "Дизел" else "Бензин / газ")
            }
            val incomplete = r.incomplete.size
            InfoCard(color = if (incomplete == 0) Palette.Ok.copy(alpha = 0.15f) else Palette.Warn.copy(alpha = 0.15f)) {
                if (incomplete == 0) {
                    Text("Всички монитори са готови", color = Palette.Ok, fontWeight = FontWeight.SemiBold)
                } else {
                    Text("Неготови монитори: $incomplete", color = Palette.Warn, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Мониторите се нулират при изтриване на грешките или откачане на акумулатора. " +
                            "Карайте 1–2 дни смесено (град и извън града), без да изтривате грешки, и проверете отново. " +
                            "Преди технически преглед е добре всички да са готови.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            for (m in r.monitors) {
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, modifier = Modifier.weight(1f))
                        if (m.complete) Chip("Готов", Palette.Ok) else Chip("Не е готов", Palette.Warn)
                    }
                }
            }
        }
    }
}

@Composable
fun FreezeScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val st = vm.freeze
    Page(
        title = "Замразени данни",
        busy = vm.busy,
        snackbar = snackbar,
        onBack = { vm.open(Screen.HOME) },
        actions = { IconButton(onClick = { vm.loadFreeze() }, enabled = vm.busy == null) { Icon(Icons.Filled.Refresh, "Обнови") } },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Стойностите, които компютърът е записал в момента на появата на грешката.",
                color = Palette.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            st.error?.let { InfoCard(color = Palette.Warn.copy(alpha = 0.15f)) { Text(it) } }
            val ff = st.data ?: return@Column
            InfoCard {
                KeyValue("Причинена от", ff.dtc ?: "—")
                ff.dtc?.let { Text(com.odit.obd.obd.DtcDatabase.describe(it), style = MaterialTheme.typography.bodySmall) }
            }
            InfoCard {
                for ((def, value) in ff.values) KeyValue(def.name, value)
                if (ff.values.isEmpty()) Text("Няма записани стойности.", color = Palette.Muted)
            }
        }
    }
}

@Composable
fun InfoScreen(vm: MainViewModel, snackbar: SnackbarHostState, onExportLog: () -> Unit) {
    val st = vm.info
    var editVin by remember { mutableStateOf(false) }
    Page(
        title = "Информация",
        busy = vm.busy,
        snackbar = snackbar,
        onBack = { vm.open(Screen.HOME) },
        actions = {
            IconButton(onClick = { vm.loadInfo() }, enabled = vm.busy == null && vm.vehicleReady) { Icon(Icons.Filled.Refresh, "Обнови") }
        },
    ) { padding ->
        SelectionContainer {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoCard {
                    Text("Автомобил", fontWeight = FontWeight.SemiBold)
                    KeyValue("VIN", vm.overview.vin ?: "не е намерен")
                    vinOrigin(vm.overview)?.let { KeyValue("Източник", it) }
                    VehicleRows(vm.vehicle, showSources = true)
                    KeyValue("Протокол", vm.protocol?.title ?: "—")
                    TextButton(onClick = { editVin = true }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (vm.overview.vin == null || vm.overview.vinManual) "Въведи VIN ръчно" else "VIN от автомобила е намерен")
                    }
                }
                val sources = vm.overview.vinReport.found
                if (sources.size > 1 && !vm.overview.vinReport.conflict) {
                    InfoCard {
                        Text("VIN е потвърден от ${sources.size} модула", fontWeight = FontWeight.SemiBold)
                        for (f in sources) Text(vinSourceText(f), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                VinConflictCard(vm.overview.vinReport)
                InfoCard {
                    Text("Адаптер", fontWeight = FontWeight.SemiBold)
                    KeyValue("Версия", vm.adapterVersion.ifBlank { "—" })
                    KeyValue("Връзка", vm.linkDescription)
                    vm.overview.voltage?.let { KeyValue("Напрежение", "%.1f V".format(java.util.Locale.US, it)) }
                }
                st.error?.let { InfoCard(color = Palette.Warn.copy(alpha = 0.15f)) { Text(it) } }
                st.data?.identities?.forEach { id ->
                    InfoCard {
                        Text(com.odit.obd.obd.ObdSession.ecuName(id.ecu), fontWeight = FontWeight.SemiBold)
                        KeyValue("Адрес", id.ecu)
                        id.name?.let { KeyValue("Име", it) }
                        for (c in id.calibrations) KeyValue("Калибровка", c)
                    }
                }
                InfoCard {
                    Text("Суров лог на комуникацията", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Ако VIN, марката или някоя грешка не се разпознават, изпратете този файл — " +
                            "съдържа само командите към адаптера и отговорите му.",
                        color = Palette.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onExportLog, contentPadding = PaddingValues(0.dp)) { Text("Експорт на лога") }
                }
                val packList = vm.dataSources()
                if (packList.isNotEmpty()) {
                    InfoCard {
                        Text("Данни и източници", fontWeight = FontWeight.SemiBold)
                        for (p in packList) {
                            Text("${p.title} · версия ${p.version}", style = MaterialTheme.typography.bodySmall)
                            for (src in p.sources) Text(src, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Odit OBD чете стандартни OBD-II данни директно от автомобила — не използва база с модели, " +
                        "интернет или реклами.",
                    color = Palette.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (editVin) {
        VinDialog(
            initial = if (vm.overview.vinManual) vm.overview.vin.orEmpty() else "",
            onSave = { vm.setManualVin(it) },
            onDismiss = { editVin = false },
        )
    }
}

@Composable
fun TerminalScreen(vm: MainViewModel, snackbar: SnackbarHostState, onExportLog: () -> Unit) {
    val version by vm.log.version.collectAsState()
    val lines = remember(version) { vm.log.snapshot() }
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }

    Page(
        title = "Терминал",
        busy = vm.busy,
        snackbar = snackbar,
        onBack = { vm.open(Screen.HOME) },
        actions = {
            IconButton(onClick = onExportLog) { Icon(Icons.Filled.Share, "Експорт на лога") }
            IconButton(onClick = { vm.log.clear() }) { Icon(Icons.Filled.Delete, "Изчисти") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Text(
                "За напреднали: командите отиват директно към адаптера. Тук се вижда и цялата комуникация.",
                color = Palette.Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    items(lines) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (c in listOf("ATI", "ATRV", "ATDP", "0100", "03", "0902")) {
                    AssistChip(onClick = { vm.sendRaw(c) }, label = { Text(c, fontSize = 12.sp) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.uppercase() },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("напр. 0105 или ATDP") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.sendRaw(text); text = "" }),
                )
                IconButton(onClick = { vm.sendRaw(text); text = "" }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Изпрати", tint = Palette.Primary)
                }
            }
        }
    }
}
