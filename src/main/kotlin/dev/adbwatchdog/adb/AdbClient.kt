package dev.adbwatchdog.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class CmdResult(val code: Int, val out: String) {
    val ok get() = code == 0
}

/** `adb mdns services` çıktısındaki bir satır. */
data class MdnsService(val name: String, val type: String, val ip: String, val port: Int) {
    val isConnect get() = type.contains("adb-tls-connect")
    val isPairing get() = type.contains("adb-tls-pairing")
    val isLegacyTcp get() = type.startsWith("_adb._tcp")
}

/** Cihaza bağlandıktan sonra okunan temel bilgiler. */
data class DeviceProps(
    val serialNo: String,
    val model: String,
    val isEmulator: Boolean,
    val bootId: String,
)

/** adb komut satırı aracının ince bir sarmalayıcısı. */
class AdbClient(@Volatile var adbPath: String) {

    @Volatile private var trackProcess: Process? = null

    /** Bir adb komutu çalıştırır; zaman aşımında süreci öldürür. */
    suspend fun run(vararg args: String, timeoutMs: Long = 15_000): CmdResult = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(listOf(adbPath) + args).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return@withContext CmdResult(-1, e.message ?: "adb çalıştırılamadı")
        }
        // Çıktıyı ayrı bir thread'de okuyoruz: adb sunucusu arka planda başlarsa
        // pipe'ı açık tutabiliyor, readText() o yüzden sonsuza kadar bekleyebilir.
        val buffer = StringBuffer()
        val reader = thread(isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { buffer.appendLine(it) }
            }
        }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@withContext CmdResult(-1, "zaman aşımı: adb ${args.joinToString(" ")}")
        }
        reader.join(500)
        CmdResult(process.exitValue(), buffer.toString().trim())
    }

    suspend fun shell(serial: String, command: String, timeoutMs: Long = 10_000) =
        run("-s", serial, "shell", command, timeoutMs = timeoutMs)

    /** adb sunucusunu çıktısını bağlamadan başlatır (yukarıdaki pipe sorununu önler). */
    suspend fun startServer() = withContext(Dispatchers.IO) {
        runCatching {
            ProcessBuilder(adbPath, "start-server")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor(15, TimeUnit.SECONDS)
        }
    }

    /**
     * `adb track-devices` akışını dinler. Her değişiklikte serial → durum haritası yayınlar
     * (durum: device, offline, unauthorized, authorizing, ...).
     * adb sunucusu yeniden başlarsa akış kendini yeniden kurar.
     */
    fun trackDevices(): Flow<Map<String, String>> = flow {
        while (currentCoroutineContext().isActive) {
            val process = runCatching {
                ProcessBuilder(adbPath, "track-devices")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }.getOrNull()

            if (process != null) {
                trackProcess = process
                try {
                    val input = process.inputStream
                    while (true) {
                        // Protokol: 4 haneli hex uzunluk + gövde
                        val header = input.readNBytes(4)
                        if (header.size < 4) break
                        val len = String(header).toIntOrNull(16) ?: break
                        val body = String(input.readNBytes(len), Charsets.UTF_8)
                        emit(parseDeviceList(body))
                    }
                } finally {
                    process.destroy()
                }
            }
            emit(emptyMap())
            delay(2_000)
        }
    }.flowOn(Dispatchers.IO)

    fun stop() {
        trackProcess?.destroy()
    }

    private fun parseDeviceList(body: String): Map<String, String> =
        body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2) parts[0] to parts[1] else null
            }
            .toMap()

    suspend fun connect(address: String): CmdResult {
        val r = run("connect", address, timeoutMs = 10_000)
        val ok = r.out.contains("connected to", ignoreCase = true) &&
            !r.out.contains("failed", ignoreCase = true) &&
            !r.out.contains("cannot", ignoreCase = true)
        return r.copy(code = if (ok) 0 else 1)
    }

    suspend fun disconnect(serial: String) = run("disconnect", serial)

    suspend fun pair(address: String, code: String): CmdResult {
        val r = run("pair", address, code, timeoutMs = 20_000)
        return r.copy(code = if (r.out.contains("Successfully paired", ignoreCase = true)) 0 else 1)
    }

    suspend fun mdnsServices(): List<MdnsService> {
        val r = run("mdns", "services", timeoutMs = 8_000)
        if (!r.ok) return emptyList()
        return r.out.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3) return@mapNotNull null
            val addr = parts.last()
            val ip = addr.substringBeforeLast(':', "")
            val port = addr.substringAfterLast(':', "").toIntOrNull()
            if (ip.isEmpty() || port == null) null
            else MdnsService(parts[0], parts[1], ip, port)
        }.toList()
    }

    suspend fun readProps(serial: String): DeviceProps? {
        val r = shell(
            serial,
            "getprop ro.serialno; getprop ro.product.model; getprop ro.kernel.qemu; " +
                "getprop ro.boot.qemu; cat /proc/sys/kernel/random/boot_id",
        )
        if (!r.ok) return null
        val lines = r.out.lines().map { it.trim() }
        if (lines.size < 5) return null
        val serialNo = lines[0].ifBlank { serial }
        return DeviceProps(
            serialNo = serialNo,
            model = lines[1].ifBlank { serialNo },
            isEmulator = serial.startsWith("emulator-") || lines[2] == "1" || lines[3] == "1",
            bootId = lines[4],
        )
    }

    /** Cihazın Wi-Fi IPv4 adresi. */
    suspend fun wlanIp(serial: String): String? {
        val r = shell(serial, "ip -f inet addr show wlan0")
        return Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(r.out)?.groupValues?.get(1)
    }

    /** Cihazın bu Wi-Fi ağında kullandığı MAC adresi (router'da sabit IP vermek için). */
    suspend fun wlanMac(serial: String): String? {
        val r = shell(serial, "ip link show wlan0")
        return Regex("link/ether ([0-9a-fA-F:]{17})").find(r.out)?.groupValues?.get(1)
    }

    suspend fun version(): String? {
        val r = run("version", timeoutMs = 5_000)
        return if (r.ok) r.out.lineSequence().firstOrNull() else null
    }
}
