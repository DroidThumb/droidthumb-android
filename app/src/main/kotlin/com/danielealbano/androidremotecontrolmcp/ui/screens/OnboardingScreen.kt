@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.components.OemBackgroundGuidanceSection
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountClaimState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.OnboardingStep
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.OnboardingViewModel
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils

/**
 * The guided first-run sequence (design doc §8.8): accessibility (which also carries the
 * restricted-settings fallback - see [OnboardingStep]'s own doc) → battery → Google sign-in.
 * [MainActivity][com.danielealbano.androidremotecontrolmcp.ui.MainActivity] shows this instead of
 * [MainScreen] until [OnboardingViewModel.step] reaches [OnboardingStep.DONE] — each step is
 * skipped automatically once its own precondition is already satisfied, re-checked on every resume
 * (the same `onResume -> refreshPermissionStatus` pattern [MainViewModel] already uses for the
 * Settings tab).
 */
@Composable
fun OnboardingScreen(
    modifier: Modifier = Modifier,
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
    mainViewModel: MainViewModel = hiltViewModel(),
    accountViewModel: AccountViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val step by onboardingViewModel.step.collectAsStateWithLifecycle()
    val accountId by accountViewModel.accountId.collectAsStateWithLifecycle()
    val claimState by accountViewModel.claimState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        onboardingViewModel.refresh(context, accountId)
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    mainViewModel.refreshPermissionStatus(context)
                    onboardingViewModel.refresh(context, accountId)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(claimState) {
        if (claimState is AccountClaimState.Claimed) onboardingViewModel.markSignedIn()
    }

    // Without this Surface, Text here falls back to Compose's hard-coded default content color
    // (black) instead of the theme's onBackground - MainScreen gets this for free from its own
    // Scaffold, which this screen has none of (found live: near-black text on the dark Google
    // Sign-In step, PR #8 phone feedback).
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (step) {
                OnboardingStep.LOADING, OnboardingStep.DONE -> {
                    Unit
                }

                OnboardingStep.ACCESSIBILITY -> {
                    AccessibilityStep()
                }

                OnboardingStep.BATTERY -> {
                    BatteryStep(
                        onAllowClick = { mainViewModel.requestBatteryOptimizationExemption() },
                        onSkipClick = { onboardingViewModel.skipBattery(context, accountId) },
                    )
                }

                OnboardingStep.GOOGLE_SIGN_IN -> {
                    GoogleSignInStep(
                        claimState = claimState,
                        onSignInClick = { accountViewModel.signInAndClaim(context) },
                        onSkipClick = { onboardingViewModel.skipSignIn() },
                    )
                }
            }
        }
    }
}

/**
 * Accessibility, plus the restricted-settings fallback (see [OnboardingStep]'s own doc for why
 * these two live on one screen rather than as separate steps): the primary action opens
 * Accessibility settings directly; if turning DroidThumb on there is blocked, the hint below
 * explains the one-time App Info detour. This step doesn't try to detect which case applies —
 * there's no platform API for that — it just shows both and lets the user follow whichever one
 * their device actually needs.
 *
 * The bold "Any problems?" sits right below the button, short enough to register at a glance
 * before tapping through to Android's own Accessibility screen — the detail below it (the actual
 * App Info steps) doesn't need reading up front, only remembering that it's there to come back to
 * if Android blocks the toggle (founder feedback, PR #8 round 5).
 *
 * Auto-advances once accessibility is actually detected enabled ([OnboardingViewModel.refresh],
 * re-run on every resume), not a manual "Continue".
 */
@Composable
private fun AccessibilityStep() {
    val context = LocalContext.current
    Text(stringResource(R.string.onboarding_accessibility_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.onboarding_accessibility_body), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(24.dp))
    Button(onClick = { PermissionUtils.openAccessibilitySettings(context) }) {
        Text(stringResource(R.string.onboarding_accessibility_action))
    }
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.onboarding_accessibility_restricted_question),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.onboarding_accessibility_restricted_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.onboarding_accessibility_restricted_steps),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = { PermissionUtils.openAppInfoSettings(context) }) {
        Text(stringResource(R.string.onboarding_accessibility_restricted_action))
    }
}

@Composable
private fun BatteryStep(
    onAllowClick: () -> Unit,
    onSkipClick: () -> Unit,
) {
    Text(stringResource(R.string.onboarding_battery_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.onboarding_battery_body), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(24.dp))
    Button(onClick = onAllowClick) { Text(stringResource(R.string.onboarding_battery_action)) }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onSkipClick) { Text("Skip for now") }
    OemBackgroundGuidanceSection()
}

@Composable
private fun GoogleSignInStep(
    claimState: AccountClaimState,
    onSignInClick: () -> Unit,
    onSkipClick: () -> Unit,
) {
    Text(stringResource(R.string.onboarding_signin_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.onboarding_signin_body), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(24.dp))
    when (claimState) {
        is AccountClaimState.SigningIn, is AccountClaimState.Claiming -> {
            CircularProgressIndicator(modifier = Modifier.height(32.dp))
        }

        is AccountClaimState.AlreadyClaimedByOther -> {
            Text(stringResource(R.string.account_already_claimed_body), color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSignInClick) { Text(stringResource(R.string.account_sign_in_action)) }
        }

        is AccountClaimState.Failed -> {
            Text(claimState.message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSignInClick) { Text(stringResource(R.string.account_sign_in_action)) }
        }

        else -> {
            Button(onClick = onSignInClick) { Text(stringResource(R.string.account_sign_in_action)) }
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onSkipClick) { Text(stringResource(R.string.onboarding_signin_skip)) }
}
