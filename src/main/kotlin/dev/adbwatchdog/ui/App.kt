package dev.adbwatchdog.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.adbwatchdog.core.LiveConn
import dev.adbwatchdog.core.Watchdog
import dev.adbwatchdog.data.AppConfig
import dev.adbwatchdog.data.DeviceStore
import dev.adbwatchdog.data.SavedDevice

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(primary = Color(0xFF7CC4A0), secondary = Color(0xFF9FB4C7))
    } else {
        lightColorScheme(primary = Color(0xFF1F7A4D), secondary = Color(0xFF4A6378))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private sealed interface DialogState {
    data class Add(val prefillIp: String?, val title: String) : DialogState
    data class Rename(val device: SavedDevice) : DialogState
    data class Forget(val device: SavedDevice) : DialogState
    data object Settings : DialogState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(watchdog: Watchdog, store: DeviceStore, currentAdbPath: () -> String?, onAdbPathChanged: (String?) -> Unit) {
    val config by store.config.collectAsState()
    val connections by watchdog.connections.collectAsState()
    val status by watchdog.status.collectAsState()
    val attention by watchdog.needsAttention.collectAsState()
    val adbVersion by watchdog.adbVersion.collectAsState()
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        watchdog.notices.collect { snackbar.showSnackbar("${it.title}: ${it.message}") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ADB Watchdog")
                        Text(
                            adbVersion ?: "adb bulunamadı",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (adbVersion == null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { watchdog.reconnectAll() }) { Icon(Icons.Default.Refresh, "Hepsini yeniden dene") }
                    IconButton(onClick = { dialog = DialogState.Settings }) { Icon(Icons.Default.Settings, "Ayarlar") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dialog = DialogState.Add(null, "Cihaz ekle") },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Cihaz ekle") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val unknown = connections.filter { it.deviceId == null }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (adbVersion == null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("adb bulunamadı veya çalışmıyor.", color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                "Android SDK platform-tools kurulu olmalı. Otomatik bulunamadıysa Ayarlar'dan adb yolunu gir.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            TextButton(onClick = { dialog = DialogState.Settings }) { Text("Ayarları aç") }
                        }
                    }
                }
            }

            if (unknown.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Tanınmayı bekleyen bağlantılar", style = MaterialTheme.typography.titleSmall)
                            unknown.forEach {
                                val hint = when (it.state) {
                                    "unauthorized" -> "telefonda izni onayla"
                                    "offline" -> "offline, sıfırlanacak"
                                    else -> it.state
                                }
                                Text("${it.serial}  –  $hint", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            if (config.devices.isEmpty() && unknown.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Henüz cihaz yok.\nUSB ile bir cihaz tak, bir emülatör aç ya da \"Cihaz ekle\" ile Wi-Fi üzerinden bağlan.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(config.devices, key = { it.id }) { device ->
                DeviceCard(
                    device = device,
                    conns = connections.filter { it.deviceId == device.id },
                    statusText = status[device.id],
                    needsAttention = device.id in attention,
                    keepAliveSeconds = config.keepAliveSeconds,
                    onConnectNow = { watchdog.connectNow(device.id) },
                    onPair = { dialog = DialogState.Add(device.lastIp ?: device.trackedIp, "${device.name}: eşleştir / bağlan") },
                    onRename = { dialog = DialogState.Rename(device) },
                    onForget = { dialog = DialogState.Forget(device) },
                    onAutoReconnect = { watchdog.setAutoReconnect(device.id, it) },
                    onOption = { option, on -> watchdog.setOption(device.id, option, on) },
                )
            }
        }
    }

    when (val d = dialog) {
        is DialogState.Add -> AddDeviceDialog(watchdog, d.prefillIp, d.title) { dialog = null }
        is DialogState.Rename -> RenameDialog(d.device.name, { dialog = null }) {
            watchdog.rename(d.device.id, it)
            dialog = null
        }
        is DialogState.Forget -> ConfirmForgetDialog(d.device.name, { dialog = null }) {
            watchdog.forget(d.device.id)
            dialog = null
        }
        DialogState.Settings -> SettingsDialog(config, currentAdbPath(), { dialog = null }) { path, keepAlive, threshold ->
            val pathChanged = path != config.adbPath
            store.update { it.copy(adbPath = path, keepAliveSeconds = keepAlive, ipWarnThreshold = threshold) }
            if (pathChanged) onAdbPathChanged(path)
            dialog = null
        }
        null -> Unit
    }
}

/** Tray ikonu rengi için genel durum. */
fun overallColor(
    cfg: AppConfig,
    conns: List<LiveConn>,
    attention: Set<String>,
    adbVersion: String?,
): Color {
    val watched = cfg.devices.filter { it.autoReconnect && !it.isEmulator && it.lastIp != null }
    val down = watched.any { d -> conns.none { it.deviceId == d.id && it.online } }
    return when {
        adbVersion == null -> Gray
        down || attention.isNotEmpty() -> Red
        conns.any { it.state == "offline" || it.state == "unauthorized" } -> Amber
        conns.any { it.online } -> Green
        else -> Gray
    }
}
