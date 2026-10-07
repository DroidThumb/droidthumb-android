package com.danielealbano.androidremotecontrolmcp.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.ui.screens.MainScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.OnboardingScreen
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.OnboardingStep
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.OnboardingViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        notificationPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        setContent {
            AndroidRemoteControlMcpTheme {
                val onboardingViewModel: OnboardingViewModel = hiltViewModel()
                val onboardingStep by onboardingViewModel.step.collectAsStateWithLifecycle()

                // LOADING (the initial value, until the first refresh resolves) and every real
                // step except DONE show the onboarding flow instead of the main app - see
                // OnboardingScreen's own doc comment for why LOADING renders blank rather than
                // flashing a wrong step for a returning user who's already done with it.
                if (onboardingStep == OnboardingStep.DONE) {
                    MainScreen(
                        onRequestNotificationPermission = ::requestNotificationPermission,
                    )
                } else {
                    OnboardingScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionStatus(this)
    }

    /**
     * Requests the POST_NOTIFICATIONS runtime permission.
     */
    private fun requestNotificationPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
