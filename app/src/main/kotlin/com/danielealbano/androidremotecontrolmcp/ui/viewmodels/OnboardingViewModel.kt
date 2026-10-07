package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.services.power.BatteryOptimizationManager
import com.danielealbano.androidremotecontrolmcp.services.transport.TransportAutoStart
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The guided first-run sequence (design doc §8.8, brought forward from the app-polish milestone):
 * accessibility → battery → Google sign-in, each shown only while actually unmet — a step whose
 * precondition is already satisfied (e.g. reinstalling onto a device that still has accessibility
 * granted) is skipped straight past, not shown and clicked through.
 *
 * There is deliberately no separate "allow restricted settings" step. Android 13+'s restricted-
 * settings block only engages — and only then does "Allow restricted settings" appear in the
 * target app's App Info ⋮ menu — *after* the user has actually attempted to turn the blocked
 * setting on from the system's own Accessibility screen; visiting App Info first (this build's
 * original ordering) shows no such menu entry at all, since nothing has tripped the block yet
 * (confirmed live, founder's phone test round 2 — the original ordering left them stuck "clicking
 * Continue" with no "Allow restricted settings" entry to tap). The real device-verified sequence
 * is: open Accessibility → tap the (grayed-out) service → get blocked → App Info → ⋮ → Allow
 * restricted settings → back to Accessibility → turn it on. [ACCESSIBILITY]'s own screen now
 * carries both the normal instructions and this restricted-settings fallback together, since
 * there is no way to know in advance whether a given device/Android version will even hit the
 * block — some don't (confirmed: this build's redroid test device never enforces it at all).
 */
enum class OnboardingStep {
    /** Initial value, before the first [OnboardingViewModel.refresh] resolves — rendered as a
     *  blank/loading frame, never as any real step's content, so a returning user who has already
     *  completed onboarding never sees a wrong step flash before landing on the main app. */
    LOADING,
    ACCESSIBILITY,
    BATTERY,
    GOOGLE_SIGN_IN,
    DONE,
}

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val batteryOptimizationManager: BatteryOptimizationManager,
        private val transportAutoStart: TransportAutoStart,
    ) : ViewModel() {
        private val _step = MutableStateFlow(OnboardingStep.LOADING)
        val step: StateFlow<OnboardingStep> = _step.asStateFlow()

        /** `accountId` comes from [AccountViewModel][com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountViewModel]
         *  (its own settings-backed state, already restored on a relaunch) - without it, a device
         *  that signed in in a previous session would show the sign-in step again on every cold
         *  start, since nothing else here remembers that it's already done. */
        fun refresh(
            context: Context,
            accountId: String?,
        ) {
            val accessibilityEnabled =
                PermissionUtils.isAccessibilityServiceEnabled(context, McpAccessibilityService::class.java)
            val batteryIgnored = batteryOptimizationManager.isIgnoringBatteryOptimizations()

            val wasDone = _step.value == OnboardingStep.DONE
            _step.value =
                when {
                    !accessibilityEnabled -> OnboardingStep.ACCESSIBILITY
                    !batteryIgnored -> OnboardingStep.BATTERY
                    accountId != null -> OnboardingStep.DONE
                    wasDone -> OnboardingStep.DONE
                    else -> OnboardingStep.GOOGLE_SIGN_IN
                }
            // The transport connects on device identity alone, not the account (claim_account is
            // sent *over* an already-connected transport, so gating this on accountId would
            // deadlock claiming) - runs on every refresh once accessibility is enabled, covering
            // both the first time that becomes true and every later cold start
            // (TransportAutoStart.maybeStart is idempotent - TransportService's own `started`
            // guard no-ops a redundant start).
            if (accessibilityEnabled) {
                viewModelScope.launch { transportAutoStart.maybeStart(context) }
            }
        }

        /** The sign-in step's own "Skip for now" — self-host deployments (today's production
         *  default, design doc D-33 Phase 2) have no account to sign into at all; forcing it would
         *  strand those installs on this screen forever. Not persisted: the next app open re-offers
         *  it, since there's no account yet to remember *not* needing one. */
        fun skipSignIn() {
            _step.value = OnboardingStep.DONE
        }

        fun markSignedIn() {
            _step.value = OnboardingStep.DONE
        }
    }
