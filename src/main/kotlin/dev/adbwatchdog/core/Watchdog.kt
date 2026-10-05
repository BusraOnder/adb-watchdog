package dev.adbwatchdog.core

import dev.adbwatchdog.adb.AdbClient
import dev.adbwatchdog.adb.CmdResult
import dev.adbwatchdog.adb.MdnsService
import dev.adbwatchdog.data.DeviceStore
import dev.adbwatchdog.data.SavedDevice
import dev.adbwatchdog.data.StabilityOption
import dev.adbwatchdog.data.with
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Uygulamanın beyni:
 *  - `adb track-devices` ile bağlantıları canlı izler
 *  - kopan Wi-Fi cihazlarını kayıtlı adres → mDNS → (gerekirse) eşleştirme sırasıyla geri bağlar
 *  - keep-alive ping'lerini atar
 *  - stabilite seçeneklerini Stabilizer'a devreder
 */
class Watchdog(private val adb: AdbClient, private val store: DeviceStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connections = MutableStateFlow<List<LiveConn>>(emptyList())
    val connections: StateFlow<List<LiveConn>> = _connections.asStateFlow()

    /** Cihaz kimliği → kullanıcıya gösterilecek durum metni. */
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()

    /** Birkaç denemede bağlanamayan, büyük ihtimalle yeniden eşleştirme gereken cihazlar. */
    private val _needsAttention = MutableStateFlow<Set<String>>(emptySet())
    val needsAttention: StateFlow<Set<String>> = _needsAttention.asStateFlow()

    private val _notices = MutableSharedFlow<AppNotice>(extraBufferCapacity = 32)
    val notices: SharedFlow<AppNotice> = _notices.asSharedFlow()

    private val _adbVersion = MutableStateFlow<String?>(null)
    val adbVersion: StateFlow<String?> = _adbVersion.asStateFlow()

    private val serialToId = ConcurrentHashMap<String, String>()
    private val identifying = ConcurrentHashMap.newKeySet<String>()
    private val downDevices = ConcurrentHashMap.newKeySet<String>()
    private val expectedDrops = ConcurrentHashMap<String, Long>()
    private val offlineSince = ConcurrentHashMap<String, Long>()
    private val keepAliveFails = ConcurrentHashMap<String, Int>()
    private val retries = ConcurrentHashMap<String, RetryState>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    @Volatile private var lastStates: Map<String, String> = emptyMap()
    private var jobs: List<Job> = emptyList()

    private class RetryState(var attempts: Int = 0, var nextAt: Long = 0)

    private val stabilizer = Stabilizer(
        adb = adb,
        store = store,
        notify = ::notify,
        expectDrop = { id -> expectedDrops[id] = System.currentTimeMillis() + 15_000 },
    )

    // ================================================================ yaşam döngüsü

    fun start() {
        jobs = listOf(
            scope.launch {
                adb.startServer()
                _adbVersion.value = adb.version()
                adb.trackDevices().collect { onDeviceList(it) }
            },
            scope.launch {
                while (isActive) {
                    delay(3_000)
                    runCatching { reconnectTick() }
                }
            },
            scope.launch {
                while (isActive) {
                    delay(store.config.value.keepAliveSeconds.coerceAtLeast(5) * 1_000L)
                    runCatching { keepAliveTick() }
                }
            },
        )
    }

    /** adb yolu değişince her şeyi yeniden başlatır. */
    fun restart(newAdbPath: String) {
        adb.stop()
        jobs.forEach { it.cancel() }
        adb.adbPath = newAdbPath
        serialToId.clear()
        lastStates = emptyMap()
        _connections.value = emptyList()
        start()
    }

    fun shutdown() {
        adb.stop()
        scope.cancel()
    }

    // ================================================================ cihaz listesi

    private fun onDeviceList(states: Map<String, String>) {
        val prev = lastStates
        lastStates = states
        val now = System.currentTimeMillis()

        states.forEach { (serial, state) ->
            if (state == "device" && !serialToId.containsKey(serial) && identifying.add(serial)) {
                scope.launch {
                    try { identify(serial) } finally { identifying.remove(serial) }
                }
            }
            if (state == "unauthorized" && prev[serial] != "unauthorized") {
                notify(AppNotice("Yetki bekleniyor", "$serial: telefonda çıkan hata ayıklama iznini onayla."))
            }
            if (state == "offline") offlineSince.putIfAbsent(serial, now) else offlineSince.remove(serial)
        }

        // Kopanları bul
        prev.filter { (serial, state) -> state == "device" && states[serial] != "device" }.keys.forEach { serial ->
            val id = serialToId[serial] ?: return@forEach
            if (!states.containsKey(serial)) serialToId.remove(serial)
            val stillOnline = states.any { (s, st) -> st == "device" && serialToId[s] == id }
            if (!stillOnline) onDeviceLost(id)
        }

        publish()
    }

    private fun onDeviceLost(id: String) {
        val d = store.device(id) ?: return
        val expected = (expectedDrops[id] ?: 0) > System.currentTimeMillis()
        downDevices += id
        setStatus(id, "Bağlantı koptu")
        if (!expected) {
            val tail = if (d.autoReconnect && !d.isEmulator && d.lastIp != null) " Yeniden bağlanılıyor…" else ""
            notify(AppNotice("Bağlantı koptu", "${d.name}.$tail", isError = true))
        }
    }

    private suspend fun identify(serial: String) {
        val props = adb.readProps(serial) ?: return
        val id = props.serialNo
        serialToId[serial] = id
        val conn = LiveConn(serial, "device", id)

        val existing = store.device(id)
        if (existing == null) {
            store.upsert(
                SavedDevice(id = id, name = props.model, isEmulator = props.isEmulator, autoReconnect = !props.isEmulator),
            )
            notify(AppNotice("Yeni cihaz", "${props.model} listeye eklendi."))
        }

        // Wi-Fi bağlantılarının adresini sakla (yeniden bağlanmak için)
        if (conn.transport == Transport.WIFI) {
            val addr = conn.ipPort ?: findMdnsAddress(serial)
            if (addr != null) store.updateDevice(id) { it.copy(lastIp = addr.first, lastPort = addr.second) }
        }

        retries.remove(id)
        _needsAttention.update { it - id }
        setStatus(id, null)
        if (downDevices.remove(id) && (expectedDrops[id] ?: 0) < System.currentTimeMillis()) {
            notify(AppNotice("Yeniden bağlandı", store.device(id)?.name ?: id))
        }
        publish()

        lock(id).withLock { stabilizer.onOnline(id, conn, props.bootId) }
    }

    /** "adb-XXXX-yyyy._adb-tls-connect._tcp" gibi mDNS serial'larının gerçek ip:port'u. */
    private suspend fun findMdnsAddress(serial: String): Pair<String, Int>? {
        val name = serial.substringBefore("._adb")
        return adb.mdnsServices().firstOrNull { it.name == name }?.let { it.ip to it.port }
    }

    private fun publish() {
        _connections.value = lastStates.map { (serial, state) -> LiveConn(serial, state, serialToId[serial]) }
    }

    // ================================================================ yeniden bağlanma

    private suspend fun reconnectTick() {
        val now = System.currentTimeMillis()

        // 10 sn'den uzun "offline" kalan bağlantıları sıfırla
        offlineSince.forEach { (serial, since) ->
            if (now - since > 10_000) {
                offlineSince[serial] = now
                if (LiveConn.isNetworkSerial(serial)) adb.disconnect(serial)
                else adb.run("-s", serial, "reconnect")
            }
        }

        var mdnsCache: List<MdnsService>? = null
        val conns = _connections.value
        for (d in store.config.value.devices) {
            if (!d.autoReconnect || d.isEmulator || d.lastIp == null) continue
            if (conns.any { it.deviceId == d.id && it.online }) continue
            if ((expectedDrops[d.id] ?: 0) > now) continue

            val retry = retries.getOrPut(d.id) { RetryState() }
            if (now < retry.nextAt) continue

            val ok = lock(d.id).withLock {
                attemptReconnect(d) { mdnsCache ?: adb.mdnsServices().also { mdnsCache = it } }
            }
            if (ok) {
                retries.remove(d.id)
                setStatus(d.id, "Bağlandı, doğrulanıyor…")
            } else {
                retry.attempts++
                val waitSec = minOf(60, 3 shl minOf(retry.attempts - 1, 5)) // 3, 6, 12, 24, 48, 60
                retry.nextAt = System.currentTimeMillis() + waitSec * 1_000L
                setStatus(d.id, "Bağlanılamadı (${retry.attempts}. deneme), $waitSec sn sonra tekrar")
                if (retry.attempts == 3) {
                    _needsAttention.update { it + d.id }
                    notify(
                        AppNotice(
                            "${d.name} bağlanamıyor",
                            "Telefonda Kablosuz hata ayıklama açık mı? Gerekirse uygulamadan \"Eşleştir\" ile yeniden eşleştir.",
                            isError = true,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun attemptReconnect(d: SavedDevice, mdns: suspend () -> List<MdnsService>): Boolean {
        setStatus(d.id, "Yeniden bağlanıyor…")

        // 1) Kayıtlı adres
        if (d.lastIp != null && d.lastPort != null && adb.connect("${d.lastIp}:${d.lastPort}").ok) return true

        // 2) mDNS: port (ya da IP) değiştiyse yenisini bul
        val candidates = mdns()
            .filter { it.isConnect || it.isLegacyTcp }
            .filter { it.name.contains("adb-${d.id}-") || it.ip == d.lastIp }
            .sortedBy { if (it.name.contains("adb-${d.id}-")) 0 else 1 }
        for (s in candidates) {
            if (adb.connect("${s.ip}:${s.port}").ok) {
                store.updateDevice(d.id) { it.copy(lastIp = s.ip, lastPort = s.port) }
                return true
            }
        }

        // 3) Sabit port açıksa 5555'i de dene
        if (d.options.fixedPort && d.lastIp != null && d.lastPort != 5555 &&
            adb.connect("${d.lastIp}:5555").ok
        ) {
            store.updateDevice(d.id) { it.copy(lastPort = 5555) }
            return true
        }
        return false
    }

    // ================================================================ keep-alive

    private suspend fun keepAliveTick() {
        val conns = _connections.value
        for (d in store.config.value.devices.filter { it.options.keepAlive }) {
            for (c in conns.filter { it.deviceId == d.id && it.online }) {
                val r = adb.shell(c.serial, "echo ok", timeoutMs = 5_000)
                if (r.ok && r.out.contains("ok")) {
                    keepAliveFails.remove(c.serial)
                } else {
                    val fails = (keepAliveFails[c.serial] ?: 0) + 1
                    keepAliveFails[c.serial] = fails
                    if (fails >= 2) {
                        // Bağlantı "device" görünüyor ama yanıt vermiyor: sıfırla, reconnect döngüsü toparlasın
                        keepAliveFails.remove(c.serial)
                        if (c.transport == Transport.WIFI) adb.disconnect(c.serial)
                        else adb.run("-s", c.serial, "reconnect")
                    }
                }
            }
        }
    }

    // ================================================================ kullanıcı eylemleri

    fun connectNow(id: String) {
        retries.remove(id)
        _needsAttention.update { it - id }
        val d = store.device(id) ?: return
        scope.launch {
            val ok = lock(id).withLock { attemptReconnect(d) { adb.mdnsServices() } }
            if (!ok) setStatus(id, "Bağlanılamadı")
        }
    }

    fun reconnectAll() {
        retries.clear()
        _needsAttention.value = emptySet()
    }

    suspend fun connectManual(address: String): CmdResult = adb.connect(address.trim())

    /**
     * Android 11+ kablosuz eşleştirme. [connectAddress] boşsa bağlantı portu mDNS'ten bulunur.
     */
    suspend fun pairAndConnect(pairAddress: String, code: String, connectAddress: String?): CmdResult {
        val pair = adb.pair(pairAddress.trim(), code.trim())
        if (!pair.ok) return pair

        connectAddress?.trim()?.takeIf { it.isNotEmpty() }?.let { return adb.connect(it) }

        val ip = pairAddress.trim().substringBeforeLast(':')
        repeat(6) {
            delay(1_500)
            val svc = adb.mdnsServices().firstOrNull { it.isConnect && it.ip == ip }
            if (svc != null) {
                val r = adb.connect("${svc.ip}:${svc.port}")
                if (r.ok) return r
            }
        }
        return CmdResult(
            1,
            "Eşleştirme başarılı ama bağlantı portu bulunamadı. Telefondaki \"IP adresi ve bağlantı noktası\" değerini girip tekrar dene.",
        )
    }

    fun setOption(id: String, option: StabilityOption, on: Boolean) {
        store.updateDevice(id) { it.copy(options = it.options.with(option, on)) }
        val conn = onlineConn(id)
        scope.launch { lock(id).withLock { stabilizer.onOptionChanged(id, option, on, conn) } }
    }

    fun setAutoReconnect(id: String, on: Boolean) {
        store.updateDevice(id) { it.copy(autoReconnect = on) }
        if (!on) {
            retries.remove(id)
            setStatus(id, null)
        }
    }

    fun rename(id: String, name: String) {
        if (name.isNotBlank()) store.updateDevice(id) { it.copy(name = name.trim()) }
    }

    /** Cihazı listeden kaldırır; bağlıysa değiştirdiğimiz ayarları önce geri alır. */
    fun forget(id: String) {
        scope.launch {
            onlineConn(id)?.let { c -> lock(id).withLock { stabilizer.restoreAll(id, c.serial) } }
            store.remove(id)
            retries.remove(id)
            downDevices.remove(id)
            setStatus(id, null)
        }
    }

    // ================================================================ yardımcılar

    private fun onlineConn(id: String) = _connections.value.firstOrNull { it.deviceId == id && it.online }

    private fun lock(id: String) = locks.getOrPut(id) { Mutex() }

    private fun setStatus(id: String, text: String?) {
        _status.update { if (text == null) it - id else it + (id to text) }
    }

    private fun notify(n: AppNotice) {
        _notices.tryEmit(n)
    }
}
