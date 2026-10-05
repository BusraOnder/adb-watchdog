package dev.adbwatchdog.core

enum class Transport { USB, WIFI, EMULATOR }

/** adb'nin şu an gördüğü tek bir bağlantı (bir cihazın hem USB hem Wi-Fi bağlantısı olabilir). */
data class LiveConn(
    val serial: String,
    val state: String,
    /** Kayıtlı cihaz kimliği (ro.serialno); henüz okunmadıysa null. */
    val deviceId: String?,
) {
    val online get() = state == "device"

    val transport: Transport
        get() = when {
            serial.startsWith("emulator-") -> Transport.EMULATOR
            isNetworkSerial(serial) -> Transport.WIFI
            else -> Transport.USB
        }

    /** "192.168.1.5:37415" biçimindeki serial'dan IP ve port. */
    val ipPort: Pair<String, Int>?
        get() {
            val m = IP_PORT.matchEntire(serial) ?: return null
            return m.groupValues[1] to m.groupValues[2].toInt()
        }

    companion object {
        private val IP_PORT = Regex("(\\d+\\.\\d+\\.\\d+\\.\\d+):(\\d+)")

        fun isNetworkSerial(serial: String) =
            IP_PORT.matches(serial) || serial.contains("._adb-tls-connect") || serial.contains("._adb._tcp")
    }
}

data class AppNotice(val title: String, val message: String, val isError: Boolean = false)
