package dev.adbwatchdog.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Ayarları ve kayıtlı cihazları JSON dosyasında tutar. */
class DeviceStore(dir: File) {

    private val file = File(dir, "config.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private fun load(): AppConfig = runCatching {
        if (file.exists()) json.decodeFromString<AppConfig>(file.readText()) else AppConfig()
    }.getOrElse { AppConfig() }

    private fun save(cfg: AppConfig) {
        runCatching {
            val tmp = File(file.parentFile, "config.json.tmp")
            tmp.writeText(json.encodeToString(AppConfig.serializer(), cfg))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun update(block: (AppConfig) -> AppConfig) {
        synchronized(lock) {
            val next = block(_config.value)
            if (next != _config.value) {
                _config.value = next
                save(next)
            }
        }
    }

    fun device(id: String): SavedDevice? = _config.value.devices.firstOrNull { it.id == id }

    fun updateDevice(id: String, block: (SavedDevice) -> SavedDevice) = update { cfg ->
        cfg.copy(devices = cfg.devices.map { if (it.id == id) block(it) else it })
    }

    fun upsert(device: SavedDevice) = update { cfg ->
        if (cfg.devices.any { it.id == device.id }) cfg
        else cfg.copy(devices = cfg.devices + device)
    }

    fun remove(id: String) = update { cfg -> cfg.copy(devices = cfg.devices.filterNot { it.id == id }) }
}
