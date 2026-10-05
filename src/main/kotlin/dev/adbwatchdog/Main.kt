package dev.adbwatchdog

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.LaunchedEffect
import dev.adbwatchdog.adb.AdbClient
import dev.adbwatchdog.core.Platform
import dev.adbwatchdog.core.Watchdog
import dev.adbwatchdog.data.DeviceStore
import dev.adbwatchdog.ui.App
import dev.adbwatchdog.ui.AppTheme
import dev.adbwatchdog.ui.overallColor
import java.awt.SystemTray

/** Tray için düz renkli daire ikonu. */
private class DotPainter(private val color: Color) : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        drawCircle(color, radius = size.minDimension * 0.38f)
    }
}

fun main() {
    val store = DeviceStore(Platform.configDir())
    var adbPath = Platform.findAdb(store.config.value.adbPath)
    val adb = AdbClient(adbPath ?: "adb")
    val watchdog = Watchdog(adb, store)
    watchdog.start()

    Runtime.getRuntime().addShutdownHook(Thread { watchdog.shutdown() })

    application {
        val traySupported = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
        val trayState = rememberTrayState()
        val windowState = rememberWindowState(width = 600.dp, height = 760.dp)
        var windowVisible by remember { mutableStateOf(true) }

        val config by store.config.collectAsState()
        val conns by watchdog.connections.collectAsState()
        val attention by watchdog.needsAttention.collectAsState()
        val adbVersion by watchdog.adbVersion.collectAsState()
        val color = overallColor(config, conns, attention, adbVersion)
        val icon = remember(color) { DotPainter(color) }

        // Sistem bildirimleri (tray destekleniyorsa)
        LaunchedEffect(traySupported) {
            if (!traySupported) return@LaunchedEffect
            watchdog.notices.collect {
                trayState.sendNotification(
                    Notification(it.title, it.message, if (it.isError) Notification.Type.Warning else Notification.Type.Info),
                )
            }
        }

        fun quit() {
            watchdog.shutdown()
            exitApplication()
        }

        if (traySupported) {
            Tray(
                icon = icon,
                state = trayState,
                tooltip = "ADB Watchdog",
                onAction = { windowVisible = true },
                menu = {
                    Item("Pencereyi aç", onClick = { windowVisible = true; windowState.isMinimized = false })
                    Item("Hepsini yeniden dene", onClick = { watchdog.reconnectAll() })
                    Separator()
                    Item("Çıkış", onClick = { quit() })
                },
            )
        }

        Window(
            onCloseRequest = {
                // Tray varsa kapatınca arka planda çalışmaya devam eder.
                // Tray desteklenmiyorsa (bazı Linux masaüstleri) pencereyi kapatmak uygulamayı kapatır;
                // arka planda tutmak için pencereyi simge durumuna küçült.
                if (traySupported) windowVisible = false else quit()
            },
            visible = windowVisible,
            state = windowState,
            title = "ADB Watchdog",
            icon = icon,
        ) {
            AppTheme {
                App(
                    watchdog = watchdog,
                    store = store,
                    currentAdbPath = { adbPath },
                    onAdbPathChanged = { custom ->
                        adbPath = Platform.findAdb(custom)
                        watchdog.restart(adbPath ?: custom ?: "adb")
                    },
                )
            }
        }
    }
}
