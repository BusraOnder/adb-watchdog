package dev.adbwatchdog.data

import kotlinx.serialization.Serializable

/** Kullanıcının cihaz başına açıp kapatabildiği stabilite seçenekleri. */
@Serializable
data class DeviceOptions(
    /** 1. adb yetkisinin 7 gün sonra düşmesini engeller. */
    val disableAuthTimeout: Boolean = false,
    /** 2. `adb tcpip 5555` ile sabit porta geçer (reboot'a kadar geçerli). */
    val fixedPort: Boolean = false,
    /** 3. Şarjdayken ekranı açık tutar ve Doze'u kapatır. */
    val preventSleep: Boolean = false,
    /** 4. IP değişimlerini sayar, sık değişirse uyarır ve MAC adresini gösterir. */
    val trackIp: Boolean = false,
    /** 5. Belirli aralıklarla ping atarak bağlantıyı canlı tutar. */
    val keepAlive: Boolean = false,
)

@Serializable
enum class StabilityOption { AUTH_TIMEOUT, FIXED_PORT, PREVENT_SLEEP, TRACK_IP, KEEP_ALIVE }

fun DeviceOptions.isOn(o: StabilityOption) = when (o) {
    StabilityOption.AUTH_TIMEOUT -> disableAuthTimeout
    StabilityOption.FIXED_PORT -> fixedPort
    StabilityOption.PREVENT_SLEEP -> preventSleep
    StabilityOption.TRACK_IP -> trackIp
    StabilityOption.KEEP_ALIVE -> keepAlive
}

fun DeviceOptions.with(o: StabilityOption, on: Boolean) = when (o) {
    StabilityOption.AUTH_TIMEOUT -> copy(disableAuthTimeout = on)
    StabilityOption.FIXED_PORT -> copy(fixedPort = on)
    StabilityOption.PREVENT_SLEEP -> copy(preventSleep = on)
    StabilityOption.TRACK_IP -> copy(trackIp = on)
    StabilityOption.KEEP_ALIVE -> copy(keepAlive = on)
}

/** Emülatörlerde ağ tabanlı seçeneklerin anlamı yok. */
fun StabilityOption.supportsEmulator() = when (this) {
    StabilityOption.FIXED_PORT, StabilityOption.TRACK_IP -> false
    else -> true
}

@Serializable
data class SavedDevice(
    /** Kalıcı kimlik: ro.serialno (IP/port değişse de sabit kalır). */
    val id: String,
    val name: String,
    val isEmulator: Boolean = false,
    val autoReconnect: Boolean = true,
    val lastIp: String? = null,
    val lastPort: Int? = null,
    val options: DeviceOptions = DeviceOptions(),
    /** Değiştirdiğimiz cihaz ayarlarının orijinal değerleri (geri almak için). */
    val backups: Map<String, String> = emptyMap(),
    /** Seçenek kapatıldığında cihaz çevrimdışıysa, bağlanınca geri alınacaklar. */
    val pendingRestore: Set<StabilityOption> = emptySet(),
    /** Seçeneklerin en son uygulandığı açılış (reboot'u anlamak için). */
    val appliedBootId: String? = null,
    /** IP takibi için: son görülen Wi-Fi IP'si ve değişim sayısı. */
    val trackedIp: String? = null,
    val ipChangeCount: Int = 0,
    val macAddress: String? = null,
)

@Serializable
data class AppConfig(
    val adbPath: String? = null,
    val keepAliveSeconds: Int = 30,
    val ipWarnThreshold: Int = 3,
    val devices: List<SavedDevice> = emptyList(),
)
