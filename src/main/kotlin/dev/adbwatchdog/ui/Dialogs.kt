package dev.adbwatchdog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.adbwatchdog.core.Watchdog
import dev.adbwatchdog.data.AppConfig
import kotlinx.coroutines.launch

/** Yeni cihaz ekleme: doğrudan ip:port ile bağlan ya da Android 11+ eşleştirme kodu ile eşleş. */
@Composable
fun AddDeviceDialog(
    watchdog: Watchdog,
    prefillIp: String?,
    title: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(if (prefillIp != null) 1 else 0) }
    var connectAddr by remember { mutableStateOf(prefillIp?.let { "$it:" } ?: "") }
    var pairAddr by remember { mutableStateOf(prefillIp?.let { "$it:" } ?: "") }
    var pairCode by remember { mutableStateOf("") }
    var pairConnectAddr by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.width(440.dp)) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0; result = null }, text = { Text("Adresle bağlan") })
                    Tab(selected = tab == 1, onClick = { tab = 1; result = null }, text = { Text("Eşleştir (Android 11+)") })
                }
                Spacer(Modifier.height(16.dp))
                if (tab == 0) {
                    Text(
                        "Telefonda Ayarlar › Geliştirici seçenekleri › Kablosuz hata ayıklama ekranındaki " +
                            "\"IP adresi ve bağlantı noktası\" değerini ya da tcpip ile açtığın adresi (örn. 192.168.1.20:5555) gir.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        connectAddr, { connectAddr = it },
                        label = { Text("IP:port") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        "Telefonda Kablosuz hata ayıklama › \"Eşleme kodu ile cihaz eşle\"ye dokun. " +
                            "Orada görünen adresi ve 6 haneli kodu gir. Bağlantı portu boş bırakılırsa otomatik bulunur.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        pairAddr, { pairAddr = it },
                        label = { Text("Eşleme adresi (IP:port)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        pairCode, { pairCode = it.filter(Char::isDigit).take(6) },
                        label = { Text("Eşleme kodu") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        pairConnectAddr, { pairConnectAddr = it },
                        label = { Text("Bağlantı adresi (opsiyonel)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                result?.let { (ok, msg) ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        msg,
                        color = if (ok) Green else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Button(
                    enabled = !busy && (if (tab == 0) connectAddr.contains(':') else (pairAddr.contains(':') && pairCode.length == 6)),
                    onClick = {
                        busy = true
                        result = null
                        scope.launch {
                            val r = if (tab == 0) watchdog.connectManual(connectAddr)
                            else watchdog.pairAndConnect(pairAddr, pairCode, pairConnectAddr.ifBlank { null })
                            busy = false
                            result = r.ok to (if (r.ok) "Bağlandı. Cihaz listeye eklenecek." else r.out.ifBlank { "Başarısız" })
                        }
                    },
                ) { Text(if (tab == 0) "Bağlan" else "Eşleştir ve bağlan") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Kapat") } },
    )
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cihazı adlandır") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Kaydet") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
fun ConfirmForgetDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$name kaldırılsın mı?") },
        text = {
            Text(
                "Cihaz listeden çıkarılır ve artık izlenmez. Cihaz şu an bağlıysa uygulamanın değiştirdiği " +
                    "ayarlar (yetki zaman aşımı, ekran/Doze) eski haline döndürülür. Tekrar bağlanırsa yeniden eklenir.",
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Kaldır") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
fun SettingsDialog(
    config: AppConfig,
    detectedAdb: String?,
    onDismiss: () -> Unit,
    onSave: (adbPath: String?, keepAliveSeconds: Int, ipWarnThreshold: Int) -> Unit,
) {
    var adbPath by remember { mutableStateOf(config.adbPath ?: "") }
    var keepAlive by remember { mutableStateOf(config.keepAliveSeconds.toString()) }
    var threshold by remember { mutableStateOf(config.ipWarnThreshold.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ayarlar") },
        text = {
            Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    adbPath, { adbPath = it },
                    label = { Text("adb yolu (boş = otomatik bul)") },
                    placeholder = { Text(detectedAdb ?: "örn. ~/Library/Android/sdk/platform-tools/adb") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Şu an kullanılan: ${detectedAdb ?: "bulunamadı"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    keepAlive, { keepAlive = it.filter(Char::isDigit) },
                    label = { Text("Keep-alive aralığı (sn)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    threshold, { threshold = it.filter(Char::isDigit) },
                    label = { Text("Kaç IP değişiminde uyarılsın") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    adbPath.trim().ifBlank { null },
                    keepAlive.toIntOrNull()?.coerceIn(5, 600) ?: 30,
                    threshold.toIntOrNull()?.coerceIn(1, 50) ?: 3,
                )
            }) { Text("Kaydet") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
