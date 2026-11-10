package com.odit.obd.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.odit.obd.obd.ObdSession
import com.odit.obd.uds.FoundModule
import com.odit.obd.vin.VinFound
import com.odit.obd.vin.VinParser
import com.odit.obd.vin.VinReport

fun vinSourceText(f: VinFound, modules: List<FoundModule> = emptyList()): String {
    val module = modules.firstOrNull { it.responseHeader == f.ecu }
    val ecu = when {
        f.ecu == "?" -> "неизвестен модул"
        module != null -> "${module.title} (${f.ecu})"
        else -> "${ObdSession.ecuName(f.ecu)} (${f.ecu})"
    }
    return "$ecu · ${f.method.title}"
}

/** Where the VIN shown came from, in one line. */
fun vinOrigin(o: Overview, modules: List<FoundModule> = emptyList()): String? = when {
    o.vinManual -> "въведен ръчно"
    else -> o.vinReport.found.firstOrNull { it.vin == o.vin }?.let { vinSourceText(it, modules) }
}

/** Warning shown when modules report different VINs. */
@Composable
fun VinConflictCard(report: VinReport, modules: List<FoundModule> = emptyList()) {
    if (!report.conflict) return
    InfoCard(color = Palette.Warn.copy(alpha = 0.15f)) {
        Text("Различни VIN в модулите", color = Palette.Warn, fontWeight = FontWeight.SemiBold)
        Text(
            "Обикновено това значи, че модул е сменен с такъв от друга кола (или не е адаптиран).",
            style = MaterialTheme.typography.bodySmall,
        )
        for (f in report.found) {
            Text(f.vin, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Text(vinSourceText(f, modules), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun VinDialog(initial: String, onSave: (String) -> String?, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val clean = text.trim().uppercase()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("VIN (номер на рамата)") },
        text = {
            Column {
                Text(
                    "Използва се само ако автомобилът не съобщава VIN. Намира се на стикер в рамката на " +
                        "вратата, под предното стъкло или в талона.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(17); error = null },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("${clean.length}/17") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                )
                error?.let { Text(it, color = Palette.Bad, style = MaterialTheme.typography.bodySmall) }
                if (VinParser.isValidFormat(clean) && !VinParser.checkDigitOk(clean)) {
                    Text(
                        "Контролната цифра (9-ти знак) не съвпада — нормално е за повечето европейски коли.",
                        color = Palette.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val e = onSave(clean)
                if (e == null) onDismiss() else error = e
            }) { Text("Запази") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отказ") } },
    )
}
