package dev.adbwatchdog.core

import java.io.File

/** İşletim sistemine özgü yollar: yapılandırma klasörü ve adb'nin konumu. */
object Platform {
    private val osName = System.getProperty("os.name").lowercase()
    val isWindows = osName.contains("win")
    val isMac = osName.contains("mac")
    val isLinux = !isWindows && !isMac

    private val home: String = System.getProperty("user.home")
    private val adbExe = if (isWindows) "adb.exe" else "adb"

    /** Ayarların saklandığı klasör (her OS'un kendi standardına göre). */
    fun configDir(): File {
        val dir = when {
            isWindows -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "AdbWatchdog")
            isMac -> File(home, "Library/Application Support/AdbWatchdog")
            else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "adb-watchdog")
        }
        dir.mkdirs()
        return dir
    }

    /**
     * adb'yi bulur. Sıra: kullanıcının girdiği yol → ANDROID_HOME / ANDROID_SDK_ROOT →
     * Android Studio'nun varsayılan SDK klasörü → bilinen sistem yolları → PATH.
     *
     * Not: macOS'ta Finder'dan açılan uygulamalar terminaldeki PATH'i görmez,
     * bu yüzden SDK klasörlerine doğrudan bakıyoruz.
     */
    fun findAdb(custom: String?): String? {
        val candidates = mutableListOf<File>()
        custom?.takeIf { it.isNotBlank() }?.let { candidates += File(it.replaceFirst(Regex("^~"), Regex.escapeReplacement(home))) }

        listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { env ->
            System.getenv(env)?.let { candidates += File(it, "platform-tools/$adbExe") }
        }

        candidates += when {
            isWindows -> listOfNotNull(
                System.getenv("LOCALAPPDATA")?.let { File(it, "Android\\Sdk\\platform-tools\\$adbExe") },
                File(home, "AppData\\Local\\Android\\Sdk\\platform-tools\\$adbExe"),
            )
            isMac -> listOf(
                File(home, "Library/Android/sdk/platform-tools/adb"),
                File("/opt/homebrew/bin/adb"),
                File("/usr/local/bin/adb"),
            )
            else -> listOf(
                File(home, "Android/Sdk/platform-tools/adb"),
                File("/usr/bin/adb"),
                File("/usr/local/bin/adb"),
            )
        }

        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { candidates += File(it, adbExe) }

        return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }
}
