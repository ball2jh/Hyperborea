package com.nettarion.hyperborea.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import com.nettarion.hyperborea.core.profile.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Owns the "sleep after idle" feature on the platform side.
 *
 * The console firmware ships with the system screen-off timeout effectively disabled, so the
 * display never sleeps. When the user enables the feature we write their chosen timeout to
 * [Settings.System.SCREEN_OFF_TIMEOUT] (the OS then handles the actual sleep), and the Activity
 * holds `FLAG_KEEP_SCREEN_ON` while a workout is active so the screen never blanks mid-ride —
 * the OS screen-off timer counts *touch* inactivity, which a pedalling user doesn't generate.
 *
 * Writing `SCREEN_OFF_TIMEOUT` needs the `WRITE_SETTINGS` special access (user-granted via
 * [Settings.ACTION_MANAGE_WRITE_SETTINGS] on API 23+; auto-granted at install below that). The
 * original timeout is saved before the first override and restored when the feature is disabled,
 * so we never leave the console permanently changed.
 */
@Singleton
class ScreenSleepController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
    @param:Named("orchestratorState")
    private val orchestratorState: StateFlow<@JvmSuppressWildcards OrchestratorState>,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) {

    private val ownPrefs = context.getSharedPreferences("screen_sleep", Context.MODE_PRIVATE)

    /**
     * Whether the Activity should hold `FLAG_KEEP_SCREEN_ON` right now: only when the feature is
     * enabled *and* a workout is in progress. When idle (or the feature is off) this is `false`
     * and the OS screen-off timeout governs.
     */
    val keepScreenOn: StateFlow<Boolean> =
        combine(userPreferences.screenSleepEnabled, orchestratorState) { enabled, state ->
            enabled && state.keepsScreenAwake()
        }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Apply the system timeout now and re-apply whenever the toggle or duration changes. */
    fun start() {
        combine(
            userPreferences.screenSleepEnabled,
            userPreferences.screenSleepTimeoutMinutes,
        ) { enabled, minutes -> enabled to minutes }
            .onEach { applyTimeout() }
            .launchIn(scope)
    }

    /** True if we may write system settings (auto-granted pre-API-23, special access from 23+). */
    fun canWriteSettings(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)

    /**
     * Intent to the system "Modify system settings" special-access screen for this app, or `null`
     * below API 23 where the permission needs no grant flow.
     */
    fun writeSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            null
        }

    /** Re-evaluate and apply the system timeout — e.g. after the user returns from granting access. */
    fun reapplyTimeout() = applyTimeout()

    private fun applyTimeout() {
        val enabled = userPreferences.screenSleepEnabled.value
        if (!canWriteSettings()) {
            if (enabled) logger.w(TAG, "Screen sleep enabled but WRITE_SETTINGS not granted — cannot set timeout")
            return
        }
        val resolver = context.contentResolver
        if (enabled) {
            if (!ownPrefs.contains(KEY_ORIGINAL_TIMEOUT)) {
                val original = Settings.System.getInt(
                    resolver, Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_TIMEOUT_MS,
                )
                ownPrefs.edit().putInt(KEY_ORIGINAL_TIMEOUT, original).apply()
                logger.i(TAG, "Saved original screen-off timeout: ${original}ms")
            }
            val targetMs = userPreferences.screenSleepTimeoutMinutes.value * MS_PER_MINUTE
            Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, targetMs)
            logger.i(TAG, "Screen-off timeout set to ${targetMs}ms")
        } else if (ownPrefs.contains(KEY_ORIGINAL_TIMEOUT)) {
            val original = ownPrefs.getInt(KEY_ORIGINAL_TIMEOUT, DEFAULT_TIMEOUT_MS)
            Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, original)
            ownPrefs.edit().remove(KEY_ORIGINAL_TIMEOUT).apply()
            logger.i(TAG, "Restored original screen-off timeout: ${original}ms")
        }
    }

    private fun OrchestratorState.keepsScreenAwake(): Boolean = when (this) {
        is OrchestratorState.Preparing,
        is OrchestratorState.AwaitingConsoleStart,
        is OrchestratorState.Running,
        OrchestratorState.Paused -> true
        OrchestratorState.Idle,
        OrchestratorState.Stopping,
        is OrchestratorState.Error -> false
    }

    private companion object {
        const val TAG = "Hyperborea.ScreenSleep"
        const val KEY_ORIGINAL_TIMEOUT = "original_screen_off_timeout"
        const val MS_PER_MINUTE = 60_000
        // Android's platform default (2 min) — only used if the system value can't be read.
        const val DEFAULT_TIMEOUT_MS = 120_000
    }
}
