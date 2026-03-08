package com.nettarion.hyperborea

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.core.LicenseChecker
import com.nettarion.hyperborea.core.LicenseState
import com.nettarion.hyperborea.ui.AppScreen
import com.nettarion.hyperborea.ui.dashboard.DashboardScreen
import com.nettarion.hyperborea.ui.profile.ProfileEditScreen
import com.nettarion.hyperborea.ui.profile.ProfilePickerScreen
import com.nettarion.hyperborea.ui.profile.ProfileStatsScreen
import com.nettarion.hyperborea.ui.theme.HyperboreaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var licenseChecker: LicenseChecker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HyperboreaTheme {
                val licenseState by licenseChecker.state.collectAsStateWithLifecycle()

                var currentScreen by remember { mutableStateOf<AppScreen?>(null) }

                LaunchedEffect(licenseState) {
                    when (licenseState) {
                        is LicenseState.Licensed -> {
                            currentScreen = AppScreen.ProfilePicker
                        }
                        is LicenseState.Unlicensed, is LicenseState.Pairing -> {
                            currentScreen = AppScreen.Dashboard
                        }
                        is LicenseState.Checking -> {
                            // Stay on current screen or show nothing
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    licenseChecker.check()
                }

                when (val screen = currentScreen) {
                    null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is AppScreen.ProfilePicker -> ProfilePickerScreen(
                        onProfileSelected = { currentScreen = AppScreen.Dashboard },
                        onCreateProfile = { currentScreen = AppScreen.ProfileEdit(null) },
                        onGuest = { currentScreen = AppScreen.Dashboard },
                    )
                    is AppScreen.Dashboard -> DashboardScreen(
                        onProfileClick = {
                            currentScreen = AppScreen.ProfileStats(it)
                        },
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
        }
    }
}
