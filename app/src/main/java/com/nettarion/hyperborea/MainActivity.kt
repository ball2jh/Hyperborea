package com.nettarion.hyperborea

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    private val pendingStopDialog = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleStopDialogIntent(intent)
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

    private fun handleStopDialogIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("show_stop_dialog", false) == true) {
            pendingStopDialog.value = true
            intent.removeExtra("show_stop_dialog")
        }
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
