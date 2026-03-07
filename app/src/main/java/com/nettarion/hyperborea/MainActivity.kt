package com.nettarion.hyperborea

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nettarion.hyperborea.ui.dashboard.DashboardScreen
import com.nettarion.hyperborea.ui.theme.HyperboreaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HyperboreaTheme {
                DashboardScreen()
            }
        }
    }
}
