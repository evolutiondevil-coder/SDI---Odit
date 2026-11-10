package com.odit.obd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Composable
fun OditApp(
    vm: MainViewModel,
    withBluetooth: (() -> Unit) -> Unit,
    onShare: (String) -> Unit,
    onSearch: (String) -> Unit,
    onExportLog: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        vm.message = null
        snackbar.showSnackbar(m)
    }

    // Keep the screen on while connected to a car.
    val view = LocalView.current
    val connected = vm.conn is ConnState.Connected
    DisposableEffect(connected) {
        view.keepScreenOn = connected
        onDispose { view.keepScreenOn = false }
    }

    BackHandler(enabled = vm.screen != Screen.CONNECT && vm.screen != Screen.HOME) {
        if (vm.screen == Screen.MODULES) vm.stopModuleScan()
        vm.back()
    }

    if (!vm.onlineAsked && vm.screen == Screen.CONNECT) OnlineIntroDialog(vm)

    when (vm.screen) {
        Screen.CONNECT -> ConnectScreen(vm, snackbar, withBluetooth)
        Screen.HOME -> HomeScreen(vm, snackbar)
        Screen.DTC -> DtcScreen(vm, snackbar, onShare, onSearch)
        Screen.MODULES -> ModulesScreen(vm, snackbar, onShare, onSearch)
        Screen.LIVE -> LiveScreen(vm, snackbar)
        Screen.READINESS -> ReadinessScreen(vm, snackbar)
        Screen.FREEZE -> FreezeScreen(vm, snackbar)
        Screen.INFO -> InfoScreen(vm, snackbar, onExportLog)
        Screen.TERMINAL -> TerminalScreen(vm, snackbar, onExportLog)
        Screen.GARAGE -> GarageScreen(vm, snackbar)
    }
}
