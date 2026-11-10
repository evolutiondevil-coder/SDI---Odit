package com.odit.obd.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.odit.obd.bt.FoundDevice

@Composable
fun ConnectScreen(vm: MainViewModel, snackbar: SnackbarHostState, withBluetooth: (() -> Unit) -> Unit) {
    Page(title = "Odit OBD", busy = null, snackbar = snackbar, onBack = null) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { StatusCard(vm) }

            val last = vm.lastDevice
            if (last != null) {
                item {
                    InfoCard {
                        Text("Последен адаптер", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                        Text(last.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(last.address, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = { withBluetooth { vm.connect(last.address, last.name) } },
                            enabled = vm.conn !is ConnState.Connecting,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Свържи") }
                    }
                }
            }

            item {
                Text("Вид връзка", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    LinkMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = vm.linkMode == mode,
                            onClick = { vm.chooseLinkMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, LinkMode.entries.size),
                        ) { Text(mode.title) }
                    }
                }
            }

            item {
                if (vm.scanning) {
                    OutlinedButton(onClick = { vm.stopScan() }, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text("Търсене… (натиснете за спиране)")
                    }
                } else {
                    Button(
                        onClick = { withBluetooth { vm.startScan() } },
                        enabled = vm.conn !is ConnState.Connecting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Търси адаптери") }
                }
            }

            items(vm.devices, key = { it.address }) { d ->
                DeviceRow(d, enabled = vm.conn !is ConnState.Connecting) {
                    withBluetooth { vm.connect(d.address, d.name, d.seenBle) }
                }
            }

            if (vm.scanning && vm.devices.isEmpty()) {
                item { Text("Търсене на устройства наблизо…", color = Palette.Muted) }
            }

            item {
                InfoCard(color = Palette.Background) {
                    Text("Как се свързва", fontWeight = FontWeight.SemiBold)
                    Text(
                        "1. Включете адаптера в OBD буксата (обикновено под волана).\n" +
                            "2. Завъртете ключа на позиция ON или запалете двигателя.\n" +
                            "3. Натиснете „Търси адаптери“ и изберете адаптера (MUCAR BT200 обикновено се казва „MUCAR…“ или „BT200…“).\n" +
                            "4. Затворете приложенията MUCAR / DollarFix — адаптерът работи само с едно приложение наведнъж.\n" +
                            "Ако телефонът поиска ПИН, опитайте 1234 или 0000.",
                        color = Palette.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (Build.VERSION.SDK_INT <= 30) {
                        Text(
                            "На тази версия на Android включете и „Местоположение“, иначе търсенето не намира устройства.",
                            color = Palette.Warn,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item {
                TextButton(onClick = { vm.connectDemo() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Демо режим (без автомобил)")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(vm: MainViewModel) {
    when (val c = vm.conn) {
        is ConnState.Connecting -> InfoCard(color = Palette.SurfaceHigh) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                Text(c.step, modifier = Modifier.weight(1f))
            }
            TextButton(onClick = { vm.disconnect() }) { Text("Отказ") }
        }
        is ConnState.Failed -> InfoCard(color = Palette.Bad.copy(alpha = 0.15f)) {
            Text("Неуспешна връзка", color = Palette.Bad, fontWeight = FontWeight.SemiBold)
            Text(c.message, style = MaterialTheme.typography.bodySmall)
        }
        else -> if (!vm.bluetoothAvailable) {
            InfoCard(color = Palette.Bad.copy(alpha = 0.15f)) {
                Text("Този телефон няма Bluetooth.", color = Palette.Bad)
            }
        }
    }
}

@Composable
private fun DeviceRow(d: FoundDevice, enabled: Boolean, onClick: () -> Unit) {
    InfoCard(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        color = if (d.looksLikeObd) Palette.SurfaceHigh else Palette.Surface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, fontWeight = FontWeight.SemiBold)
                Text(d.address, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            d.rssi?.let { Text("$it dBm", color = Palette.Muted, style = MaterialTheme.typography.bodySmall) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (d.looksLikeObd) Chip("OBD адаптер", Palette.Ok)
            if (d.seenBle) Chip("BLE", Palette.Primary)
            if (d.seenClassic) Chip("Класически", Palette.Primary)
            if (d.bonded) Chip("Сдвоен", Palette.Muted)
        }
    }
}
