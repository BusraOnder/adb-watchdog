package dev.adbwatchdog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.adbwatchdog.core.LiveConn
import dev.adbwatchdog.core.Transport
import dev.adbwatchdog.data.SavedDevice
import dev.adbwatchdog.data.StabilityOption
import dev.adbwatchdog.data.isOn
import dev.adbwatchdog.data.supportsEmulator

val Green = Color(0xFF2E9E5B)
val Amber = Color(0xFFE0A100)
val Red = Color(0xFFD64545)
val Gray = Color(0xFF8A8F98)

data class OptionInfo(val option: StabilityOption, val title: String, val description: String)

fun optionInfos(keepAliveSeconds: Int) = listOf(
    OptionInfo(
        StabilityOption.AUTH_TIMEOUT,
        "Yetki zaman aşımını kapat",
        "adb izni 7 gün sonra kendiliğinden düşmez. Kapatınca cihazdaki eski değer geri yüklenir.",
    ),
    OptionInfo(
        StabilityOption.FIXED_PORT,
        "Sabit port (5555)",
        "Bağlantıyı \"adb tcpip 5555\" ile sabit porta alır, port her seferinde değişmez. " +
            "Cihaz yeniden başlayınca USB ya da kablosuz hata ayıklamayla bağlandığında otomatik tekrar uygulanır.",
    ),
    OptionInfo(
        StabilityOption.PREVENT_SLEEP,
        "Uyku ve Doze'u engelle",
        "Şarjdayken ekran açık kalır ve Doze kapanır, Wi-Fi uykuya geçmez. Pil tüketimini artırır; kapatınca ayarlar geri alınır.",
    ),
    OptionInfo(
        StabilityOption.TRACK_IP,
        "IP değişimini izle",
        "IP sık değişirse uyarır ve router'da sabit IP tanımlayabilmen için cihazın MAC adresini gösterir.",
    ),
    OptionInfo(
        StabilityOption.KEEP_ALIVE,
        "Keep-alive",
        "$keepAliveSeconds sn'de bir ping atar. Yanıt vermeyen bağlantıyı hemen sıfırlayıp yeniden bağlar.",
    ),
)

@Composable
fun DeviceCard(
    device: SavedDevice,
    conns: List<LiveConn>,
    statusText: String?,
    needsAttention: Boolean,
    keepAliveSeconds: Int,
    onConnectNow: () -> Unit,
    onPair: () -> Unit,
    onRename: () -> Unit,
    onForget: () -> Unit,
    onAutoReconnect: (Boolean) -> Unit,
    onOption: (StabilityOption, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val online = conns.any { it.online }
    val dotColor = when {
        online -> Green
        conns.any { it.state == "unauthorized" || it.state == "offline" } -> Amber
        device.autoReconnect && device.lastIp != null && !device.isEmulator -> Red
        else -> Gray
    }
    val enabledCount = StabilityOption.entries.count { device.options.isOn(it) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // ---- Başlık
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        connectionSummary(device, conns),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRename) { Icon(Icons.Default.Edit, "Yeniden adlandır") }
                IconButton(onClick = onForget) { Icon(Icons.Default.Delete, "Kaldır") }
            }

            if (statusText != null && !online) {
                Spacer(Modifier.size(8.dp))
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (needsAttention && !online) {
                Spacer(Modifier.size(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Otomatik bağlanılamadı. Telefonda Kablosuz hata ayıklamayı açıp yeniden eşleştirmen gerekebilir.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onPair) { Text("Eşleştir") }
                    }
                }
            }

            // ---- Otomatik yeniden bağlanma + butonlar
            if (!device.isEmulator) {
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Koparsa otomatik yeniden bağlan", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = device.autoReconnect, onCheckedChange = onAutoReconnect)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!online && device.lastIp != null) {
                        OutlinedButton(onClick = onConnectNow) {
                            Icon(Icons.Default.Link, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Şimdi bağlan")
                        }
                    }
                    OutlinedButton(onClick = onPair) {
                        Icon(Icons.Default.Wifi, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Eşleştir / adres gir")
                    }
                }
            }

            // ---- Stabilite seçenekleri
            Spacer(Modifier.size(8.dp))
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Bağlantı stabilitesi ($enabledCount/5 açık)",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }

            if (expanded) {
                optionInfos(keepAliveSeconds).forEach { info ->
                    val supported = !device.isEmulator || info.option.supportsEmulator()
                    OptionRow(
                        info = info,
                        checked = device.options.isOn(info.option) && supported,
                        enabled = supported,
                        onChange = { onOption(info.option, it) },
                    )
                    if (info.option == StabilityOption.TRACK_IP && device.options.trackIp && supported) {
                        Text(
                            buildString {
                                append("IP: ${device.trackedIp ?: "—"}   ·   Değişim: ${device.ipChangeCount}")
                                device.macAddress?.let { append("   ·   MAC: $it") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                        )
                    }
                }
                if (!online && device.pendingRestore.isNotEmpty()) {
                    Text(
                        "Kapattığın bazı ayarlar cihaz tekrar bağlandığında geri alınacak.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(info: OptionInfo, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                info.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (enabled) info.description else "Emülatörde geçerli değil.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun connectionSummary(device: SavedDevice, conns: List<LiveConn>): String {
    if (conns.isEmpty()) {
        return when {
            device.isEmulator -> "Emülatör · kapalı"
            device.lastIp != null -> "Bağlı değil · son adres ${device.lastIp}:${device.lastPort ?: "?"}"
            else -> "Bağlı değil"
        }
    }
    return conns.joinToString("   ·   ") { c ->
        val transport = when (c.transport) {
            Transport.USB -> "USB"
            Transport.WIFI -> "Wi-Fi ${c.ipPort?.let { "${it.first}:${it.second}" } ?: ""}".trim()
            Transport.EMULATOR -> "Emülatör (${c.serial})"
        }
        val state = when (c.state) {
            "device" -> "bağlı"
            "offline" -> "offline"
            "unauthorized" -> "izin bekliyor"
            "authorizing" -> "yetkilendiriliyor"
            else -> c.state
        }
        "$transport – $state"
    }
}
