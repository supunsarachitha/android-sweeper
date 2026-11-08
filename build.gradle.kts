plugins {
    id("com.android.application") version "9.5.1" apply false
    // AGP 9 compiles Kotlin itself (built-in Kotlin). Declaring the Kotlin plugin
    // here only selects the Kotlin version it uses: AGP alone would bring 2.2.10,
    // which is affected by CVE-2026-53914 (fixed in 2.4.20).
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
