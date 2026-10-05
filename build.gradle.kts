import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
}

group = "dev.adbwatchdog"
version = "1.0.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}

compose.desktop {
    application {
        mainClass = "dev.adbwatchdog.MainKt"

        nativeDistributions {
            // macOS: .dmg, Windows: .msi, Linux: .deb / .rpm
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "ADB Watchdog"
            packageVersion = "1.0.0"
            description = "ADB bağlantılarını izler ve kopunca otomatik yeniden bağlar"
            vendor = "ADB Watchdog"

            macOS {
                bundleID = "dev.adbwatchdog"
                // Dock'ta görünmesin, sadece menü çubuğunda dursun
                infoPlist {
                    extraKeysRawXml = """
                        <key>LSUIElement</key>
                        <true/>
                    """.trimIndent()
                }
            }
            windows {
                menuGroup = "ADB Watchdog"
                perUserInstall = true
                upgradeUuid = "6f1c2a8e-3b7d-4e59-9a41-2c8d5e7f9b10"
            }
            linux {
                packageName = "adb-watchdog"
            }
        }
    }
}
