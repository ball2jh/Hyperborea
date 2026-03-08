package com.nettarion.hyperborea

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.core.LicenseState
import com.nettarion.hyperborea.ui.AppScreen
import com.nettarion.hyperborea.ui.dashboard.DashboardScreen
import com.nettarion.hyperborea.ui.license.LicenseViewModel
import com.nettarion.hyperborea.ui.license.PairingScreen
import com.nettarion.hyperborea.ui.license.UnlicensedScreen
import com.nettarion.hyperborea.ui.profile.ProfileEditScreen
import com.nettarion.hyperborea.ui.profile.ProfilePickerScreen
import com.nettarion.hyperborea.ui.profile.ProfileStatsScreen
import com.nettarion.hyperborea.ui.theme.HyperboreaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HyperboreaTheme {
                val licenseVm: LicenseViewModel = hiltViewModel()
                val licenseState by licenseVm.licenseState.collectAsStateWithLifecycle()

                when (val state = licenseState) {
                    is LicenseState.Checking -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is LicenseState.Unlicensed -> {
                        UnlicensedScreen(onLinkDevice = licenseVm::requestPairing)
                    }
                    is LicenseState.Pairing -> {
                        PairingScreen(
                            pairingToken = state.pairingToken,
                            pairingCode = state.pairingCode,
                            expiresAt = state.expiresAt,
                            onCancel = licenseVm::cancelPairing,
                        )
                    }
                    is LicenseState.Licensed -> {
                        MainApp(onUnlinkDevice = licenseVm::unlinkDevice)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainApp(onUnlinkDevice: () -> Unit) {
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.ProfilePicker) }

    when (val screen = currentScreen) {
        is AppScreen.ProfilePicker -> ProfilePickerScreen(
            onProfileSelected = { currentScreen = AppScreen.Dashboard },
            onCreateProfile = { currentScreen = AppScreen.ProfileEdit(null) },
            onGuest = { currentScreen = AppScreen.Dashboard },
        )
        is AppScreen.Dashboard -> DashboardScreen(
            onProfileClick = { currentScreen = AppScreen.ProfileStats(it) },
            onUnlinkDevice = onUnlinkDevice,
        )
        is AppScreen.ProfileEdit -> ProfileEditScreen(
            profileId = screen.profileId,
            onSaved = { currentScreen = AppScreen.Dashboard },
            onBack = { currentScreen = AppScreen.ProfilePicker },
        )
        is AppScreen.ProfileStats -> ProfileStatsScreen(
            profileId = screen.profileId,
            onBack = { currentScreen = AppScreen.Dashboard },
            onEditProfile = { currentScreen = AppScreen.ProfileEdit(screen.profileId) },
            onSwitchProfile = { currentScreen = AppScreen.ProfilePicker },
        )
    }
}
