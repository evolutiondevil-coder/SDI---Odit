package com.odit.obd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.odit.obd.garage.CarRecord
import com.odit.obd.garage.ScanRecord
import com.odit.obd.vehicle.Confidence
import com.odit.obd.vehicle.Field
import com.odit.obd.vehicle.UserVehicle
import com.odit.obd.vehicle.VehicleIdentity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Golf V (вероятно)", "2008", "1.4 TSI (ръчно)". */
fun fieldText(f: Field<*>): String = when (f.confidence) {
    Confidence.CERTAIN -> f.value.toString()
    Confidence.PROBABLE -> "${f.value} (вероятно)"
    Confidence.ONLINE -> "${f.value} (онлайн, вероятно)"
    Confidence.USER -> "${f.value} (ръчно)"
}

/** Title for the vehicle card: only values that are not guesses (guesses are in the rows, marked). */
fun vehicleTitle(v: VehicleIdentity): String? =
    listOfNotNull(v.make?.takeIf { !it.probable }?.value, v.model?.takeIf { !it.probable }?.value)
        .joinToString(" ").ifBlank { null }

@Composable
fun VehicleRows(v: VehicleIdentity, showSources: Boolean = false) {
    if (v.make != null) KeyValue("Марка", fieldText(v.make)) else if (v.maker != null) KeyValue("Производител", v.maker)
    if (v.make == null && v.country != null) KeyValue("Държава", "${v.country} (по VIN)")
    v.model?.let {
        KeyValue("Модел", fieldText(it))
        if (showSources) KeyValue("", it.source)
    }
    when {
        v.year != null -> KeyValue("Година", fieldText(v.year))
        v.yearNote != null && v.vin != null -> KeyValue("Година", "не се вижда от VIN")
    }
    if (showSources && v.yearNote != null) KeyValue("", v.yearNote)
    v.engine?.let { KeyValue("Двигател", fieldText(it)) }
    if (v.make != null && v.country != null) KeyValue("Произход", v.country)
}

@Composable
fun EditVehicleDialog(v: VehicleIdentity, user: UserVehicle, onSave: (UserVehicle) -> Unit, onDismiss: () -> Unit) {
    var make by remember { mutableStateOf(user.make ?: v.make?.value.orEmpty()) }
    var model by remember { mutableStateOf(user.model ?: v.model?.value.orEmpty()) }
    var year by remember { mutableStateOf((user.year ?: v.year?.value)?.toString().orEmpty()) }
    var engine by remember { mutableStateOf(user.engine ?: v.engine?.value.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Данни за автомобила") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Поправете каквото е грешно или липсва. Запомня се за този автомобил и има предимство пред " +
                        "разчетеното от VIN и интернет. Празно поле връща автоматичната стойност.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(make, { make = it }, label = { Text("Марка") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text("Модел") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    year, { year = it.filter { c -> c.isDigit() }.take(4) }, label = { Text("Година") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(engine, { engine = it }, label = { Text("Двигател (напр. 1.9 TDI)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Only values that differ from the automatic ones become corrections.
                fun pick(text: String, auto: String?) = text.trim().takeIf { it.isNotEmpty() && it != auto }
                onSave(
                    UserVehicle(
                        make = pick(make, v.make?.takeIf { it.confidence != Confidence.USER }?.value),
                        model = pick(model, v.model?.takeIf { it.confidence != Confidence.USER }?.value),
                        year = pick(year, v.year?.takeIf { it.confidence != Confidence.USER }?.value?.toString())?.toIntOrNull()
                            ?.takeIf { it in 1950..2100 },
                        engine = pick(engine, v.engine?.takeIf { it.confidence != Confidence.USER }?.value),
                    ),
                )
                onDismiss()
            }) { Text("Запази") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отказ") } },
    )
}

private val dateFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US)

private fun historyText(h: ScanRecord): String {
    val what = when (h.kind) {
        ScanRecord.OBD -> "Грешки на двигателя"
        ScanRecord.MODULES -> "Сканиране на модулите"
        ScanRecord.CLEAR -> "Изтриване"
        else -> h.kind
    }
    val codes = if (h.codes.isEmpty()) "няма грешки" else h.codes.joinToString(", ")
    return "${dateFmt.format(Date(h.time))} · $what${h.note?.let { " ($it)" }.orEmpty()}: $codes"
}

@Composable
fun GarageScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<CarRecord?>(null) }
    Page(title = "Гараж", busy = null, snackbar = snackbar, onBack = { vm.closeGarage() }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.garageCars.isEmpty()) {
                item {
                    Text(
                        "Тук ще се появят автомобилите, с които сте се свързвали: марка, модел, открити модули и " +
                            "история на сканиранията. Всичко се пази само на телефона.",
                        color = Palette.Muted,
                    )
                }
            }
            items(vm.garageCars, key = { it.id }) { c ->
                val id = vm.identityOf(c)
                InfoCard(modifier = Modifier.clickable { expanded = if (expanded == c.id) null else c.id }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(vehicleTitle(id) ?: c.name ?: "Автомобил", fontWeight = FontWeight.SemiBold)
                            Text(
                                c.vin ?: "без VIN",
                                fontFamily = FontFamily.Monospace,
                                color = Palette.Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(dateFmt.format(Date(c.lastSeen)), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (expanded == c.id) {
                        VehicleRows(id)
                        c.protocol?.let { p -> com.odit.obd.elm.ObdProtocol.fromCode(p)?.let { KeyValue("Протокол", it.title) } }
                        if (c.modules.isNotEmpty()) {
                            KeyValue("Модули", c.modules.joinToString { it.name ?: it.ident ?: it.header })
                        }
                        SectionTitle("История (${c.history.size})")
                        for (h in c.history.reversed().take(20)) {
                            Text(historyText(h), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { confirmDelete = c }, contentPadding = PaddingValues(0.dp)) {
                            Text("Изтрий от гаража", color = Palette.Bad)
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Изтриване от гаража?") },
            text = { Text("Изтриват се запомнените данни и историята за този автомобил.") },
            confirmButton = { TextButton(onClick = { vm.deleteCar(c.id); confirmDelete = null }) { Text("Изтрий", color = Palette.Bad) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отказ") } },
        )
    }
}
