package com.odit.obd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odit.obd.obd.Dtc
import com.odit.obd.obd.EcuDtc
import com.odit.obd.obd.ObdSession
import com.odit.obd.uds.FoundModule

@Composable
fun DtcScreen(vm: MainViewModel, snackbar: SnackbarHostState, onShare: (String) -> Unit, onSearch: (String) -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    val d = vm.dtc
    Page(
        title = "Грешки на двигателя",
        busy = vm.busy,
        snackbar = snackbar,
        onBack = { vm.open(Screen.HOME) },
        actions = {
            IconButton(onClick = { vm.readDtcs() }, enabled = vm.busy == null) { Icon(Icons.Filled.Refresh, "Прочети отново") }
            IconButton(onClick = { onShare(vm.buildReport()) }, enabled = d.loaded) { Icon(Icons.Filled.Share, "Сподели") }
        },
    ) { padding ->
        if (d.loading && !d.loaded) {
            Column(Modifier.padding(padding)) { CenteredLoading("Четене на грешките…") }
            return@Page
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            d.error?.let { err ->
                item { InfoCard(color = Palette.Bad.copy(alpha = 0.15f)) { Text(err, color = Palette.Bad) } }
            }
            if (d.loaded && d.total == 0) {
                item {
                    InfoCard(color = Palette.Ok.copy(alpha = 0.15f)) {
                        Text("Няма грешки", color = Palette.Ok, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Компютърът на двигателя и скоростната кутия не съобщават за грешки. " +
                                "За ABS, въздушни възглавници и другите модули използвайте „Всички модули“.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            dtcSection("Запаметени", "Потвърдени грешки — те палят лампата на двигателя.", d.stored, onSearch, vm)
            dtcSection("Изчакващи", "Открити веднъж; ако се повторят, стават запаметени.", d.pending, onSearch, vm)
            dtcSection(
                "Постоянни",
                "Не се изтриват с уред — изчезват сами, след като автомобилът провери, че проблемът е отстранен.",
                d.permanent, onSearch, vm,
            )
            if (d.loaded) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { confirmClear = true },
                        enabled = vm.busy == null,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Bad),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Изтрий грешките") }
                }
            }
        }
    }
    if (confirmClear) ClearDialog(onConfirm = { confirmClear = false; vm.clearDtcs() }, onDismiss = { confirmClear = false })
}

private fun androidx.compose.foundation.lazy.LazyListScope.dtcSection(
    title: String,
    hint: String,
    list: List<EcuDtc>,
    onSearch: (String) -> Unit,
    vm: MainViewModel,
) {
    if (list.isEmpty()) return
    item {
        SectionTitle("$title (${list.size})")
        Text(hint, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
    }
    items(list, key = { title + it.ecu + it.dtc.code }) { e ->
        DtcCard(e.dtc, ObdSession.ecuName(e.ecu), onSearch, vm.overview.vinInfo?.make)
    }
}

@Composable
fun DtcCard(dtc: Dtc, source: String?, onSearch: (String) -> Unit, make: String?) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                dtc.displayCode,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = kindColor(dtc.kind),
            )
            Chip(dtc.kind.title, kindColor(dtc.kind))
            source?.let { Chip(it, Palette.Muted) }
        }
        Text(dtc.description, style = MaterialTheme.typography.bodyMedium)
        dtc.ftbDescription?.let { Text("Тип: $it", color = Palette.Muted, style = MaterialTheme.typography.bodySmall) }
        TextButton(
            onClick = { onSearch(listOfNotNull(dtc.code, make).joinToString(" ")) },
            contentPadding = PaddingValues(0.dp),
        ) { Text("Търси в интернет") }
    }
}

@Composable
fun ModulesScreen(vm: MainViewModel, snackbar: SnackbarHostState, onShare: (String) -> Unit, onSearch: (String) -> Unit) {
    var confirm by remember { mutableStateOf<List<FoundModule>?>(null) }
    val m = vm.modules
    val isCan = vm.protocol?.isCan == true
    Page(
        title = "Всички модули",
        busy = if (m.scanning) null else vm.busy,
        snackbar = snackbar,
        onBack = { vm.stopModuleScan(); vm.open(Screen.HOME) },
        actions = {
            IconButton(onClick = { onShare(vm.buildReport()) }, enabled = m.modules.isNotEmpty() && !m.scanning) {
                Icon(Icons.Filled.Share, "Сподели")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!isCan) {
                item {
                    InfoCard(color = Palette.Warn.copy(alpha = 0.15f)) {
                        Text(
                            "Този автомобил използва ${vm.protocol?.title ?: "стар протокол"}. Сканирането на всички модули " +
                                "работи само с CAN автомобили (обикновено от 2008 г. насам). Грешките на двигателя са достъпни.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                InfoCard {
                    Text(
                        "Пита всеки диагностичен адрес за грешки — не е нужен модел или VIN. " +
                            "Бързото проверява известните адреси (~15 сек.), пълното — всички (~1 мин.).",
                        color = Palette.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (m.scanning) {
                        val progress = if (m.total > 0) m.done.toFloat() / m.total else 0f
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "Проверка на ${m.current} · ${m.done}/${m.total} · намерени: ${m.modules.size}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { vm.stopModuleScan() }, modifier = Modifier.fillMaxWidth()) { Text("Спри") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.scanModules(full = false) },
                                enabled = isCan && vm.busy == null,
                                modifier = Modifier.weight(1f),
                            ) { Text("Бързо") }
                            OutlinedButton(
                                onClick = { vm.scanModules(full = true) },
                                enabled = isCan && vm.busy == null,
                                modifier = Modifier.weight(1f),
                            ) { Text("Пълно") }
                        }
                    }
                }
            }
            m.error?.let { err -> item { InfoCard(color = Palette.Bad.copy(alpha = 0.15f)) { Text(err, color = Palette.Bad) } } }
            if (m.finished && !m.scanning) {
                item {
                    val withCodes = m.modules.count { it.dtcs.isNotEmpty() }
                    Text(
                        "Намерени модули: ${m.modules.size} · с грешки: $withCodes",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            items(m.modules, key = { it.responseHeader }) { mod ->
                ModuleCard(mod, enabled = vm.busy == null && !m.scanning, onClear = { confirm = listOf(mod) }, onSearch = onSearch, make = vm.overview.vinInfo?.make)
            }
            val withCodes = m.modules.filter { it.dtcs.isNotEmpty() }
            if (withCodes.size > 1 && !m.scanning) {
                item {
                    Button(
                        onClick = { confirm = withCodes },
                        enabled = vm.busy == null,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Bad),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Изтрий грешките във всички модули") }
                }
            }
        }
    }
    confirm?.let { list ->
        ClearDialog(
            title = if (list.size == 1) "Изтриване: ${list[0].title}?" else "Изтриване в ${list.size} модула?",
            onConfirm = { confirm = null; vm.clearModules(list) },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun ModuleCard(m: FoundModule, enabled: Boolean, onClear: () -> Unit, onSearch: (String) -> Unit, make: String?) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.title, fontWeight = FontWeight.SemiBold)
                val sub = listOfNotNull(m.addressLabel, m.identification?.takeIf { m.catalogName != null }).joinToString(" · ")
                Text(sub, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            when {
                m.dtcs.isNotEmpty() -> Chip(errorsText(m.dtcs.size), Palette.Bad)
                m.readError != null -> Chip("?", Palette.Warn)
                else -> Chip("OK", Palette.Ok)
            }
        }
        m.readError?.let { Text(it, color = Palette.Warn, style = MaterialTheme.typography.bodySmall) }
        for (dtc in m.dtcs) {
            HorizontalDivider(color = Palette.SurfaceHigh)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dtc.displayCode, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = kindColor(dtc.kind))
                Chip(dtc.kind.title, kindColor(dtc.kind))
            }
            Text(dtc.description, style = MaterialTheme.typography.bodySmall)
            dtc.ftbDescription?.let { Text("Тип: $it", color = Palette.Muted, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { onSearch(listOfNotNull(dtc.code, make).joinToString(" ")) }, contentPadding = PaddingValues(0.dp)) {
                Text("Търси в интернет")
            }
        }
        if (m.dtcs.isNotEmpty()) {
            OutlinedButton(onClick = onClear, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("Изтрий грешките в този модул")
            }
        }
    }
}
