package com.odit.obd.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

fun onlineExplanation(sends: String): String =
    "Когато има интернет, приложението може:\n" +
        "• да попита безплатната услуга NHTSA vPIC (САЩ) за марката, модела, годината и двигателя — " +
        "изпраща се само $sends;\n" +
        "• да изтегля обновени таблици (производители, модели, кодове на грешки) от GitHub хранилището на приложението.\n\n" +
        "Не се изпращат грешки, местоположение, телефонни данни или друго. Няма реклами, аналитика и проследяване. " +
        "Всичко изтеглено се пази на телефона и после работи и без интернет. " +
        "Без интернет приложението работи както досега."

/** Asked once, at the first start. */
@Composable
fun OnlineIntroDialog(vm: MainViewModel) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Онлайн помощ") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(onlineExplanation(vm.onlineSends), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Можете да го промените по всяко време от „Информация“.",
                    color = Palette.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { vm.setOnline(true) }) { Text("Включи") } },
        dismissButton = { TextButton(onClick = { vm.setOnline(false) }) { Text("Не, без интернет") } },
    )
}

@Composable
fun OnlineSettingCard(vm: MainViewModel) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Онлайн помощ", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Switch(checked = vm.onlineEnabled, onCheckedChange = { vm.setOnline(it) })
        }
        Text(onlineExplanation(vm.onlineSends), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        if (vm.onlineEnabled) {
            val at = vm.packsCheckedAt
            Text(
                if (at == 0L) "Данните още не са проверявани за обновления."
                else "Последна проверка за обновени данни: " +
                    java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.US).format(java.util.Date(at)),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { vm.maybeUpdatePacks(force = true) }, enabled = !vm.packsUpdating) {
                Text(if (vm.packsUpdating) "Проверка…" else "Провери за обновени данни")
            }
        }
    }
}

@Composable
fun OnlineBusyNote(vm: MainViewModel) {
    if (!vm.onlineBusy) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.width(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("Търсене на допълнителни данни онлайн…", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
    }
}
