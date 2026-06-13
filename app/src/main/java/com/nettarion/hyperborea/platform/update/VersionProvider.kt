package com.nettarion.hyperborea.platform.update

/**
 * The app's installed version, read at runtime from [android.content.pm.PackageManager] (see the
 * binding in `UpdateModule`). This — not `BuildConfig.VERSION_NAME`/`VERSION_CODE` — is the single
 * source of truth for what's actually installed: the `BuildConfig` fields are AGP-managed and can
 * be restored stale from the Gradle build cache (a 1.2.18 build once shipped reporting 1.2.17 while
 * its manifest correctly said 10218). The manifest value PackageManager returns can't drift from
 * itself, and matches Google's guidance to read the runtime version from PackageManager.
 */
interface VersionProvider {
    fun getVersionCode(): Int
    fun getVersionName(): String
}
