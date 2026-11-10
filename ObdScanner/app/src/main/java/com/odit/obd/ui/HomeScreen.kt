package com.odit.obd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun HomeScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var editVin by remember { mutableStateOf(false) }

    Page(
        title = if (vm.demo) "Автомобил (демо)" else "Автомобил",
        busy = vm.busy,
        snackbar = snackbar,
        onBack = null,
        actions = {
            IconButton(onClick = { vm.refreshOverview() }, enabled = vm.vehicleReady) {
                Icon(Icons.Filled.Refresh, contentDescription = "Обнови")
            }
            IconButton(onClick = { confirmExit = true }) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Прекъсни връзката")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VehicleCard(vm, onEditVin = { editVin = true })
            VinConflictCard(vm.overview.vinReport)

            vm.vehicleError?.let { err ->
                InfoCard(color = Palette.Warn.copy(alpha = 0.15f)) {
                    Text("Няма връзка с автомобила", color = Palette.Warn, fontWeight = FontWeight.SemiBold)
                    Text(err, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.retryVehicle() }, enabled = vm.busy == null) { Text("Опитай отново") }
                }
            }

            val ready = vm.vehicleReady
            TileRow(
                Tile("Грешки на двигателя", "Четене (OBD)", Icons.Filled.Warning, Palette.Bad, ready) { vm.open(Screen.DTC) },
                Tile("Всички модули", "ABS, Airbag, табло…", Icons.Filled.Build, Palette.Primary, ready) { vm.open(Screen.MODULES) },
            )
            TileRow(
                Tile("Изтрий грешките", "Двигател (OBD)", Icons.Filled.Delete, Palette.Warn, ready) {
                    if (ready) confirmClear = true else vm.open(Screen.DTC)
                },
                Tile("Данни на живо", "Обороти, температура…", Icons.Filled.PlayArrow, Palette.Ok, ready) { vm.open(Screen.LIVE) },
            )
            TileRow(
                Tile("Готовност", "Монитори (за ГТП)", Icons.Filled.CheckCircle, Palette.Ok, ready) { vm.open(Screen.READINESS) },
                Tile("Замразени данни", "При появата на грешка", Icons.Filled.DateRange, Palette.Primary, ready) { vm.open(Screen.FREEZE) },
            )
            TileRow(
                Tile("Информация", "VIN, модули, версии", Icons.Filled.Info, Palette.Primary, true) { vm.open(Screen.INFO) },
                Tile("Терминал", "Ръчни команди", Icons.AutoMirrored.Filled.Send, Palette.Muted, true) { vm.open(Screen.TERMINAL) },
            )

            Spacer(Modifier.height(4.dp))
            Text(
                "Сервизните нулирания (масло, DPF, спирачки) и тестовете на компоненти са различни за всяка марка " +
                    "и не са включени — за тях използвайте приложението на производителя.",
                color = Palette.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (editVin) {
        VinDialog(
            initial = if (vm.overview.vinManual) vm.overview.vin.orEmpty() else "",
            onSave = { vm.setManualVin(it) },
            onDismiss = { editVin = false },
        )
    }
    if (confirmClear) ClearDialog(onConfirm = { confirmClear = false; vm.clearDtcs() }, onDismiss = { confirmClear = false })
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Прекъсване на връзката?") },
            confirmButton = { TextButton(onClick = { confirmExit = false; vm.disconnect() }) { Text("Прекъсни") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Отказ") } },
        )
    }
}

@Composable
fun ClearDialog(title: String = "Изтриване на грешките?", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                "• Запалването трябва да е включено, а двигателят — изгасен.\n" +
                    "• Ще се изтрият и замразените данни, а мониторите за готовност ще се нулират " +
                    "(преди ГТП трябва да се покара известно време).\n" +
                    "• Ако причината не е отстранена, грешката ще се появи отново.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Изтрий", color = Palette.Bad) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отказ") } },
    )
}

@Composable
private fun VehicleCard(vm: MainViewModel, onEditVin: () -> Unit) {
    val o = vm.overview
    InfoCard {
        val title = o.vinInfo?.let { listOfNotNull(it.make, it.year?.toString()).joinToString(" · ") }
            ?.ifBlank { null } ?: if (vm.vehicleReady) "Автомобил" else "Адаптерът е свързан"
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (o.vin != null) {
            KeyValue("VIN", o.vin)
            vinOrigin(o)?.let { KeyValue("", it) }
        } else if (vm.vehicleReady && vm.busy == null) {
            KeyValue("VIN", "не е намерен")
        }
        if (vm.vehicleReady && vm.busy == null && (o.vin == null || o.vinManual)) {
            TextButton(onClick = onEditVin, contentPadding = PaddingValues(0.dp)) {
                Text(if (o.vin == null) "Въведи VIN ръчно" else "Промени ръчно въведения VIN")
            }
        }
        vm.protocol?.let { KeyValue("Протокол", it.title) }
        KeyValue("Адаптер", listOf(vm.adapterVersion, vm.linkDescription).filter { it.isNotBlank() }.joinToString(" · "))
        o.voltage?.let { v ->
            val color = when {
                v < 11.8 -> Palette.Bad
                v < 12.2 -> Palette.Warn
                else -> Palette.Ok
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Акумулатор",
                    color = Palette.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(130.dp),
                )
                Chip("%.1f V".format(Locale.US, v), color)
            }
        }
        o.readiness?.let { r ->
            Spacer(Modifier.height(4.dp))
            if (r.milOn) {
                Chip("Лампата на двигателя СВЕТИ · ${errorsText(r.dtcCount)}", Palette.Bad)
            } else {
                Chip("Лампата на двигателя не свети", Palette.Ok)
            }
        }
    }
}

private class Tile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun TileRow(a: Tile, b: Tile) {
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TileView(a, Modifier.weight(1f).fillMaxHeight())
        TileView(b, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun TileView(t: Tile, modifier: Modifier) {
    val alpha = if (t.enabled) 1f else 0.4f
    InfoCard(modifier = modifier.clickable(onClick = t.onClick)) {
        Icon(t.icon, contentDescription = null, tint = t.color.copy(alpha = alpha), modifier = Modifier.size(28.dp))
        Text(t.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        Text(t.subtitle, color = Palette.Muted.copy(alpha = alpha), style = MaterialTheme.typography.bodySmall)
    }
}
