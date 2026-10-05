package dev.adbwatchdog.core

import dev.adbwatchdog.adb.AdbClient
import dev.adbwatchdog.data.DeviceStore
import dev.adbwatchdog.data.StabilityOption
import dev.adbwatchdog.data.StabilityOption.AUTH_TIMEOUT
import dev.adbwatchdog.data.StabilityOption.FIXED_PORT
import dev.adbwatchdog.data.StabilityOption.KEEP_ALIVE
import dev.adbwatchdog.data.StabilityOption.PREVENT_SLEEP
import dev.adbwatchdog.data.StabilityOption.TRACK_IP
import dev.adbwatchdog.data.isOn
import dev.adbwatchdog.data.supportsEmulator
import kotlinx.coroutines.delay

/**
 * Kopmaları azaltan, cihaz başına açılıp kapatılabilen ayarları uygular ve geri alır.
 * Değiştirilen her cihaz ayarının orijinal değeri SavedDevice.backups içinde saklanır.
 */
class Stabilizer(
    private val adb: AdbClient,
    private val store: DeviceStore,
    private val notify: (AppNotice) -> Unit,
    /** Kasıtlı olarak bağlantıyı düşüreceğimizi Watchdog'a bildirir (yanlış "koptu" bildirimi olmasın). */
    private val expectDrop: (deviceId: String) -> Unit,
) {
    private companion object {
        const val KEY_AUTH = "adb_allowed_connection_time"
        const val KEY_STAY_ON = "stay_on_while_plugged_in"
        const val KEY_DOZE = "deviceidle"
        const val FIXED_PORT_NO = 5555
    }

    /** Cihaz her çevrimiçi olduğunda çağrılır. */
    suspend fun onOnline(id: String, conn: LiveConn, bootId: String) {
        var d = store.device(id) ?: return

        // Cihaz çevrimdışıyken kapatılan seçenekleri şimdi geri al
        if (d.pendingRestore.isNotEmpty()) {
            d.pendingRestore.forEach { restore(id, conn.serial, it) }
            store.updateDevice(id) { it.copy(pendingRestore = emptySet()) }
            d = store.device(id) ?: return
        }

        val rebooted = d.appliedBootId != bootId
        StabilityOption.entries
            .filter { d.options.isOn(it) && (!d.isEmulator || it.supportsEmulator()) }
            .forEach { apply(id, conn, it, rebooted) }

        store.updateDevice(id) { it.copy(appliedBootId = bootId) }
    }

    /** Kullanıcı bir seçeneği açtı/kapattı. [conn] null ise cihaz şu an bağlı değil. */
    suspend fun onOptionChanged(id: String, option: StabilityOption, on: Boolean, conn: LiveConn?) {
        if (on) {
            store.updateDevice(id) { it.copy(pendingRestore = it.pendingRestore - option) }
            if (conn != null) apply(id, conn, option, rebooted = true)
            return
        }
        when {
            option == TRACK_IP -> restore(id, null, option) // sadece yerel sayaç, cihaza gerek yok
            conn != null -> restore(id, conn.serial, option)
            option == AUTH_TIMEOUT || option == PREVENT_SLEEP ->
                store.updateDevice(id) { it.copy(pendingRestore = it.pendingRestore + option) }
            else -> Unit
        }
    }

    /** Cihaz kaldırılırken açık olan her şeyi geri al. */
    suspend fun restoreAll(id: String, serial: String) {
        val d = store.device(id) ?: return
        StabilityOption.entries.filter { d.options.isOn(it) || it in d.pendingRestore }
            .forEach { restore(id, serial, it) }
    }

    // ---------------------------------------------------------------- uygula

    private suspend fun apply(id: String, conn: LiveConn, option: StabilityOption, rebooted: Boolean) {
        val serial = conn.serial
        when (option) {
            // 1) Yetki zaman aşımını kapat (Developer Options'taki ayarın kendisi)
            AUTH_TIMEOUT -> {
                backup(id, serial, KEY_AUTH)
                adb.shell(serial, "settings put global $KEY_AUTH 0")
            }

            // 2) Sabit port: adb tcpip 5555
            FIXED_PORT -> applyFixedPort(id, conn, rebooted)

            // 3) Uyku/Doze engelle
            PREVENT_SLEEP -> {
                backup(id, serial, KEY_STAY_ON)
                adb.shell(serial, "settings put global $KEY_STAY_ON 7") // AC + USB + kablosuz şarj
                adb.shell(serial, "dumpsys deviceidle disable")         // reboot'ta sıfırlanır, her bağlanışta yeniden
                store.updateDevice(id) { it.copy(backups = it.backups + (KEY_DOZE to "enable")) }
            }

            // 4) IP değişimlerini izle
            TRACK_IP -> trackIp(id, serial)

            // 5) Keep-alive: Watchdog'daki periyodik döngü yapıyor
            KEEP_ALIVE -> Unit
        }
    }

    private val tcpipInProgress = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private suspend fun applyFixedPort(id: String, conn: LiveConn, rebooted: Boolean) {
        if (conn.transport == Transport.EMULATOR) return
        val currentPort = conn.ipPort?.second
        if (currentPort == FIXED_PORT_NO) return
        // USB bağlantısında her takışta adbd'yi yeniden başlatmamak için sadece reboot sonrası / ilk açılışta
        val needed = when (conn.transport) {
            Transport.WIFI -> true          // kablosuz hata ayıklamanın değişken portu → 5555'e geç
            Transport.USB -> rebooted
            Transport.EMULATOR -> false
        }
        if (!needed || !tcpipInProgress.add(id)) return

        try {
            val ip = adb.wlanIp(conn.serial)
            if (ip == null) {
                notify(AppNotice("Sabit port açılamadı", "Cihazın Wi-Fi IP'si okunamadı. Wi-Fi'ye bağlı mı?", true))
                return
            }
            expectDrop(id)
            adb.run("-s", conn.serial, "tcpip", FIXED_PORT_NO.toString())
            delay(2_500)
            var ok = false
            repeat(4) {
                if (!ok) {
                    ok = adb.connect("$ip:$FIXED_PORT_NO").ok
                    if (!ok) delay(1_500)
                }
            }
            if (ok) {
                store.updateDevice(id) { it.copy(lastIp = ip, lastPort = FIXED_PORT_NO) }
                if (conn.transport == Transport.WIFI) adb.disconnect(conn.serial) // eski TLS bağlantısı
                notify(AppNotice("Sabit porta geçildi", "$ip:$FIXED_PORT_NO (cihaz yeniden başlayana kadar geçerli)"))
            } else {
                notify(AppNotice("Sabit port", "$ip:$FIXED_PORT_NO adresine bağlanılamadı", true))
            }
        } finally {
            tcpipInProgress.remove(id)
        }
    }

    private suspend fun trackIp(id: String, serial: String) {
        val ip = adb.wlanIp(serial) ?: return
        val mac = adb.wlanMac(serial)
        val threshold = store.config.value.ipWarnThreshold
        var warn: Pair<String, Int>? = null
        store.updateDevice(id) { d ->
            val changed = d.trackedIp != null && d.trackedIp != ip
            val count = if (changed) d.ipChangeCount + 1 else d.ipChangeCount
            if (changed && count >= threshold && count % threshold == 0) warn = d.name to count
            d.copy(trackedIp = ip, ipChangeCount = count, macAddress = mac ?: d.macAddress)
        }
        warn?.let { (name, count) ->
            notify(
                AppNotice(
                    "$name: IP sık değişiyor",
                    "IP $count kez değişti. Router'da ${mac ?: "cihazın MAC adresine"} için sabit IP (DHCP rezervasyonu) tanımla.",
                ),
            )
        }
    }

    // ---------------------------------------------------------------- geri al

    private suspend fun restore(id: String, serial: String?, option: StabilityOption) {
        when (option) {
            AUTH_TIMEOUT -> serial?.let { restoreSetting(id, it, KEY_AUTH) }
            PREVENT_SLEEP -> serial?.let {
                restoreSetting(id, it, KEY_STAY_ON)
                if (store.device(id)?.backups?.containsKey(KEY_DOZE) == true) {
                    adb.shell(it, "dumpsys deviceidle enable")
                    store.updateDevice(id) { d -> d.copy(backups = d.backups - KEY_DOZE) }
                }
            }
            TRACK_IP -> store.updateDevice(id) { it.copy(trackedIp = null, ipChangeCount = 0) }
            // tcpip modu cihaz yeniden başlayınca kendiliğinden kapanır; `adb usb` Wi-Fi'yi keseceği için çağırmıyoruz
            FIXED_PORT, KEEP_ALIVE -> Unit
        }
    }

    /** Ayarın orijinal değerini sadece ilk seferde kaydeder. */
    private suspend fun backup(id: String, serial: String, key: String) {
        if (store.device(id)?.backups?.containsKey(key) == true) return
        val r = adb.shell(serial, "settings get global $key")
        if (!r.ok) return
        val value = r.out.trim()
        store.updateDevice(id) { it.copy(backups = it.backups + (key to value)) }
    }

    private suspend fun restoreSetting(id: String, serial: String, key: String) {
        val original = store.device(id)?.backups?.get(key) ?: return
        val r = if (original.isBlank() || original == "null") {
            adb.shell(serial, "settings delete global $key")
        } else {
            adb.shell(serial, "settings put global $key $original")
        }
        if (r.ok) store.updateDevice(id) { it.copy(backups = it.backups - key) }
    }
}
