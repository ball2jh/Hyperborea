package com.nettarion.hyperborea.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import com.nettarion.hyperborea.core.profile.OverlayStyle
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.platform.ScreenSleepController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Backs the "Display" tab in [SettingsScreen]. Exposes and mutates the global
 * units preference — read by the dashboard, profile screens, and ride summaries —
 * plus the screen-presentation toggles (overlay, immersive mode, screen sleep).
 */
@HiltViewModel
class DisplaySettingsViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val screenSleepController: ScreenSleepController,
) : ViewModel() {

    val useImperial: StateFlow<Boolean> = userPreferences.useImperial
    val overlayEnabled: StateFlow<Boolean> = userPreferences.overlayEnabled
    val overlayStyle: StateFlow<OverlayStyle> = userPreferences.overlayStyle
    val immersiveModeEnabled: StateFlow<Boolean> = userPreferences.immersiveModeEnabled
    val screenSleepEnabled: StateFlow<Boolean> = userPreferences.screenSleepEnabled
    val screenSleepTimeoutMinutes: StateFlow<Int> = userPreferences.screenSleepTimeoutMinutes

    fun setUseImperial(enabled: Boolean) {
        userPreferences.setUseImperial(enabled)
    }

    fun setOverlayEnabled(enabled: Boolean) {
        userPreferences.setOverlayEnabled(enabled)
    }

    fun setOverlayStyle(style: OverlayStyle) {
        userPreferences.setOverlayStyle(style)
    }

    fun setImmersiveModeEnabled(enabled: Boolean) {
        userPreferences.setImmersiveModeEnabled(enabled)
    }

    fun setScreenSleepEnabled(enabled: Boolean) {
        userPreferences.setScreenSleepEnabled(enabled)
    }

    fun setScreenSleepTimeoutMinutes(minutes: Int) {
        userPreferences.setScreenSleepTimeoutMinutes(minutes)
    }

    /** True if we already hold WRITE_SETTINGS; if false the toggle must route through [writeSettingsIntent]. */
    fun canWriteSettings(): Boolean = screenSleepController.canWriteSettings()

    /** Intent to the system "Modify system settings" screen, or null below API 23 (no grant needed). */
    fun writeSettingsIntent(): Intent? = screenSleepController.writeSettingsIntent()

    /** Re-apply the system timeout after returning from the permission screen. */
    fun reapplyScreenSleep() {
        screenSleepController.reapplyTimeout()
    }

    /**
     * Start watching for the WRITE_SETTINGS grant so the app can pull itself back to the foreground
     * once granted — these consoles have no nav bar for the user to return on their own. Call this
     * right before launching the system settings screen.
     */
    fun beginAwaitWriteSettingsGrant() {
        screenSleepController.awaitWriteSettingsGrant()
    }
}
