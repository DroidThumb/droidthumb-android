package com.danielealbano.androidremotecontrolmcp.services.transport

import android.content.Context
import android.content.Intent
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts [TransportService] with no user action (design doc §8.8 revision): the connection comes
 * up on its own once accessibility is enabled, and again after a reboot or an app update — the
 * only thing left for the user to control is Pause. Called from three places:
 * [OnboardingViewModel][com.danielealbano.androidremotecontrolmcp.ui.viewmodels.OnboardingViewModel]
 * whenever accessibility is enabled, [TransportBootReceiver] on `BOOT_COMPLETED`, and
 * [TransportPackageReplacedReceiver] on `MY_PACKAGE_REPLACED`.
 *
 * Deliberately NOT gated on having a claimed account: the device identity (Keystore key), not the
 * account, is what the transport connects and authenticates with — `claim_account` is sent *over*
 * an already-connected transport (`AccountViewModel.signInAndClaim` → `DeviceTransportClient
 * .claimAccount`), so requiring an account first would deadlock claiming entirely (confirmed live:
 * founder's phone test round 2, "Not connected to the server" on every sign-in attempt after an
 * earlier version of this file required `accountId != null` here). A self-hosted device that skips
 * sign-in entirely (design doc D-33 Phase 2) also legitimately runs with no account, ever.
 *
 * A paused device is deliberately left stopped here rather than started-then-immediately-gated:
 * [DeviceTransportClientImpl]'s own pause check only covers `step` dispatch, which needs a live
 * connection to answer over — starting the service anyway while paused would hold that connection
 * open for nothing since there is nothing it would currently be allowed to do with it. [resume]
 * (called when the user explicitly unpauses) re-runs this same check, so a resume while the
 * prerequisites are already met starts the service exactly the way enabling accessibility does.
 */
@Singleton
class TransportAutoStart
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) {
        suspend fun maybeStart(context: Context) {
            val accessibilityEnabled =
                PermissionUtils.isAccessibilityServiceEnabled(context, McpAccessibilityService::class.java)
            val paused = settingsRepository.getPauseState().isEffectivePause(System.currentTimeMillis())
            if (!accessibilityEnabled || paused) {
                Logger.i(TAG, "Not auto-starting: accessibility=$accessibilityEnabled paused=$paused")
                return
            }
            settingsRepository.updateTransportEnabled(true)
            val intent =
                Intent(context, TransportService::class.java).apply { action = TransportService.ACTION_START }
            context.startForegroundService(intent)
            Logger.i(TAG, "Transport auto-started")
        }

        private companion object {
            private const val TAG = "MCP:TransportAutoStart"
        }
    }
