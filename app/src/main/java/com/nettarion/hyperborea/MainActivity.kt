package com.nettarion.hyperborea

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nettarion.hyperborea.platform.ScreenSleepController
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.platform.update.TrackState
import com.nettarion.hyperborea.ui.AppScreen
import com.nettarion.hyperborea.ui.admin.AdminViewModel
import com.nettarion.hyperborea.ui.admin.UpdateDialog
import com.nettarion.hyperborea.ui.dashboard.DashboardScreen
import com.nettarion.hyperborea.ui.profile.ProfileEditScreen
import com.nettarion.hyperborea.ui.profile.ProfilePickerScreen
import com.nettarion.hyperborea.ui.profile.ProfileStatsScreen
import com.nettarion.hyperborea.ui.device.DeviceConfigScreen
import com.nettarion.hyperborea.ui.ride.RideDetailScreen
import com.nettarion.hyperborea.ui.settings.SettingsScreen
import com.nettarion.hyperborea.ui.theme.HyperboreaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var screenSleepController: ScreenSleepController

    private val pendingStopDialog = mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                Log.w(TAG, "Permissions not granted: $denied — affected features will degrade")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestRuntimePermissions()
        }
        handleStopDialogIntent(intent)
        observeKeepScreenOn()
        enableEdgeToEdge()
        setContent {
            HyperboreaTheme {
                MainApp(pendingStopDialog = pendingStopDialog)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStopDialogIntent(intent)
    }

    /**
     * Hold `FLAG_KEEP_SCREEN_ON` while the controller reports a workout is active (and the
     * sleep-after-idle feature is on), releasing it when idle so the system screen-off timeout
     * can take over. No-op when the feature is disabled. Scoped to STARTED so it only manages the
     * flag while this Activity is visible.
     */
    private fun observeKeepScreenOn() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                screenSleepController.keepScreenOn.collect { keepOn ->
                    if (keepOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }

    private fun handleStopDialogIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("show_stop_dialog", false) == true) {
            pendingStopDialog.value = true
            intent.removeExtra("show_stop_dialog")
        }
    }

    /**
     * Best-effort runtime-permission request on first launch. On the supported deploy path
     * (`deploy.sh` uses `adb install -g`) these are already granted, so no dialog appears;
     * this is the safety net for other install paths. Anything denied just degrades a feature
     * — BLE FTMS, HRM scanning, or live notification updates — handled at each use site.
     */
    private fun requestRuntimePermissions() {
        val needed = requiredRuntimePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requiredRuntimePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ Bluetooth model: GATT server + advertising (FTMS) and scanning (HRM).
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // API 23–30: BLE scanning (HRM discovery only) needs location at runtime.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val TAG = "Hyperborea.MainActivity"
    }
}

@Composable
private fun MainApp(
    pendingStopDialog: MutableState<Boolean>,
) {
    val adminViewModel: AdminViewModel = hiltViewModel()
    val appTrackState by adminViewModel.appTrackState.collectAsStateWithLifecycle()

    if (appTrackState != TrackState.Idle) {
        UpdateDialog(
            trackState = appTrackState,
            onUpdateNow = adminViewModel::applyUpdate,
            onLater = adminViewModel::dismissUpdate,
            onDismissError = adminViewModel::dismissUpdate,
        )
    }

    var backStack by remember { mutableStateOf(listOf<AppScreen>(AppScreen.ProfilePicker())) }
    val currentScreen = backStack.last()

    fun navigateTo(screen: AppScreen) { backStack = backStack + screen }
    fun navigateBack() { if (backStack.size > 1) backStack = backStack.dropLast(1) }
    fun navigateReplace(screen: AppScreen) { backStack = listOf(screen) }

    BackHandler(enabled = backStack.size > 1) { navigateBack() }

    when (val screen = currentScreen) {
        is AppScreen.ProfilePicker -> ProfilePickerScreen(
            onProfileSelected = { navigateReplace(AppScreen.Dashboard) },
            onCreateProfile = { navigateTo(AppScreen.ProfileEdit(null)) },
            onGuest = { navigateReplace(AppScreen.Dashboard) },
            autoSelect = screen.autoSelect,
        )
        is AppScreen.Dashboard -> DashboardScreen(
            onProfileClick = { navigateTo(AppScreen.ProfileStats(it)) },
            onSwitchProfile = { navigateReplace(AppScreen.ProfilePicker(autoSelect = false)) },
            onViewRide = { rideId -> navigateTo(AppScreen.RideDetail(rideId)) },
            onOpenSettings = { navigateTo(AppScreen.Settings) },
            pendingStopDialog = pendingStopDialog,
        )
        is AppScreen.ProfileEdit -> ProfileEditScreen(
            profileId = screen.profileId,
            onSaved = {
                if (screen.profileId != null) navigateBack()
                else navigateReplace(AppScreen.Dashboard)
            },
            onBack = { navigateBack() },
            onDeleted = { navigateReplace(AppScreen.ProfilePicker()) },
        )
        is AppScreen.ProfileStats -> ProfileStatsScreen(
            profileId = screen.profileId,
            onBack = { navigateBack() },
            onEditProfile = { navigateTo(AppScreen.ProfileEdit(screen.profileId)) },
            onSwitchProfile = { navigateReplace(AppScreen.ProfilePicker(autoSelect = false)) },
            onRideClick = { rideId -> navigateTo(AppScreen.RideDetail(rideId)) },
        )
        is AppScreen.RideDetail -> RideDetailScreen(
            rideId = screen.rideId,
            onBack = { navigateBack() },
        )
        is AppScreen.Settings -> SettingsScreen(
            onBack = { navigateBack() },
            onConfigureDevice = { modelNumber -> navigateTo(AppScreen.DeviceConfig(modelNumber)) },
        )
        is AppScreen.DeviceConfig -> DeviceConfigScreen(
            modelNumber = screen.modelNumber,
            onSaved = { navigateBack() },
            onBack = { navigateBack() },
        )
    }
}
