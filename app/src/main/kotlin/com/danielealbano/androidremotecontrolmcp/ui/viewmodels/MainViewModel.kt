package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService
import com.danielealbano.androidremotecontrolmcp.services.power.BatteryOptimizationManager
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * State for the home and permissions screens: whether the accessibility service, notification
 * permission, notification listener and battery-optimization exemption are in place.
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val batteryOptimizationManager: BatteryOptimizationManager,
    ) : ViewModel() {
        private val _isAccessibilityEnabled = MutableStateFlow(false)
        val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

        private val _isNotificationPermissionGranted = MutableStateFlow(false)
        val isNotificationPermissionGranted: StateFlow<Boolean> = _isNotificationPermissionGranted.asStateFlow()

        private val _isBatteryOptimizationIgnored = MutableStateFlow(false)
        val isBatteryOptimizationIgnored: StateFlow<Boolean> = _isBatteryOptimizationIgnored.asStateFlow()

        private val _isNotificationListenerEnabled = MutableStateFlow(false)
        val isNotificationListenerEnabled: StateFlow<Boolean> = _isNotificationListenerEnabled.asStateFlow()

        fun refreshPermissionStatus(context: Context) {
            _isAccessibilityEnabled.value =
                PermissionUtils.isAccessibilityServiceEnabled(
                    context,
                    McpAccessibilityService::class.java,
                )
            _isNotificationPermissionGranted.value =
                PermissionUtils.isNotificationPermissionGranted(context)
            _isNotificationListenerEnabled.value =
                PermissionUtils.isNotificationListenerEnabled(
                    context,
                    McpNotificationListenerService::class.java,
                )
            _isBatteryOptimizationIgnored.value = batteryOptimizationManager.isIgnoringBatteryOptimizations()
        }

        fun requestBatteryOptimizationExemption() {
            batteryOptimizationManager.requestExemption()
        }
    }
