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
 * restricted settings → accessibility → battery → Google sign-in, each shown only while actually
 * unmet — a step whose precondition is already satisfied (e.g. reinstalling onto a device that
 * still has accessibility granted) is skipped straight past, not shown and clicked through.
 *
 * [RESTRICTED_SETTINGS] has no direct platform API to query ("is this app currently exempted from
 * Android 13+'s restricted-settings block") — the only observable proxy is whether the
 * accessibility service it blocks is already enabled: if it is, restricted settings can't still be
 * blocking it (or this Android version/install source never imposed the restriction to begin
 * with), so that step and the accessibility step both skip together. While accessibility is still
 * off, this step's own "Continue" is a plain forward button (the user did the ⋮ menu step or
 * didn't; there's nothing to re-check mid-flow), not a detector.
 */
enum class OnboardingStep {
    /** Initial value, before the first [OnboardingViewModel.refresh] resolves — rendered as a
     *  blank/loading frame, never as any real step's content, so a returning user who has already
     *  completed onboarding never sees a wrong step flash before landing on the main app. */
    LOADING,
    RESTRICTED_SETTINGS,
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

        /** Manually advanced past by the restricted-settings step's own "Continue" (see the class
         *  doc) — there's nothing to auto-detect there, unlike every other step below. */
        private var restrictedSettingsAcknowledged = false

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
                    !accessibilityEnabled && !restrictedSettingsAcknowledged -> OnboardingStep.RESTRICTED_SETTINGS
                    !accessibilityEnabled -> OnboardingStep.ACCESSIBILITY
                    !batteryIgnored -> OnboardingStep.BATTERY
                    accountId != null -> OnboardingStep.DONE
                    wasDone -> OnboardingStep.DONE
                    else -> OnboardingStep.GOOGLE_SIGN_IN
                }
            // Covers both the first time this becomes true and every later cold start where it's
            // already true (TransportAutoStart.maybeStart is idempotent - TransportService's own
            // `started` guard no-ops a redundant start) - the transport otherwise has no other way
            // to come back up between the boot/app-update triggers if something else stopped it.
            if (_step.value == OnboardingStep.DONE && accountId != null) {
                viewModelScope.launch { transportAutoStart.maybeStart(context) }
            }
        }

        fun acknowledgeRestrictedSettings(
            context: Context,
            accountId: String?,
        ) {
            restrictedSettingsAcknowledged = true
            refresh(context, accountId)
        }

        /** The sign-in step's own "Skip for now" — self-host deployments (today's production
         *  default, design doc D-33 Phase 2) have no account to sign into at all; forcing it would
         *  strand those installs on this screen forever. Not persisted: the next app open re-offers
         *  it, since there's no account yet to remember *not* needing one. */
        fun skipSignIn() {
            _step.value = OnboardingStep.DONE
        }

        fun markSignedIn(context: Context) {
            _step.value = OnboardingStep.DONE
            viewModelScope.launch { transportAutoStart.maybeStart(context) }
        }
    }
