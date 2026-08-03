package com.nettarion.hyperborea.demo

import android.os.Build

/**
 * Debug-build helper deciding whether to substitute the demo hardware adapter. Only true on
 * emulators — a debug APK side-loaded onto a real console still talks to the real FitPro USB
 * hardware, so the debug/release behaviour only diverges where real hardware can't exist.
 */
object DemoMode {
    val isEmulator: Boolean =
        Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm") ||
            Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("google/sdk_") ||
            Build.PRODUCT.contains("sdk")
}
