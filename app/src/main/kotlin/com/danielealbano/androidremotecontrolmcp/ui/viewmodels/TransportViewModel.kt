package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.TransportConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.transport.DeviceTransportClient
import com.danielealbano.androidremotecontrolmcp.services.transport.TransportService
import com.danielealbano.androidremotecontrolmcp.services.transport.TransportStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransportViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val transportClient: DeviceTransportClient,
        @ApplicationContext private val appContext: Context,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        enum class RegenerateSecretState { IDLE, IN_PROGRESS, SUCCEEDED, TIMED_OUT }

        val transportConfig: StateFlow<TransportConfig> =
            settingsRepository.transportConfig
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TransportConfig())

        val transportStatus: StateFlow<TransportStatus> = TransportService.serviceStatus

        val connectorUrl: StateFlow<String?> =
            settingsRepository.connectorUrl
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        private val _hostInput = MutableStateFlow("")
        val hostInput: StateFlow<String> = _hostInput.asStateFlow()
        private val _portInput = MutableStateFlow(TransportConfig.DEFAULT_PORT.toString())
        val portInput: StateFlow<String> = _portInput.asStateFlow()
        private val _portError = MutableStateFlow<String?>(null)
        val portError: StateFlow<String?> = _portError.asStateFlow()
        private val _tlsInput = MutableStateFlow(false)
        val tlsInput: StateFlow<Boolean> = _tlsInput.asStateFlow()

        private val _regenerateState = MutableStateFlow(RegenerateSecretState.IDLE)
        val regenerateState: StateFlow<RegenerateSecretState> = _regenerateState.asStateFlow()

        init {
            viewModelScope.launch {
                transportConfig.collect { config ->
                    _hostInput.value = config.host
                    _portInput.value = config.port.toString()
                    _tlsInput.value = config.tls
                }
            }
        }

        fun updateTls(tls: Boolean) {
            _tlsInput.value = tls
            viewModelScope.launch(ioDispatcher) { settingsRepository.updateTransportTls(tls) }
        }

        fun regenerateSecret() {
            _regenerateState.value = RegenerateSecretState.IN_PROGRESS
            viewModelScope.launch(ioDispatcher) {
                val succeeded = transportClient.regenerateSecret()
                _regenerateState.value = if (succeeded) RegenerateSecretState.SUCCEEDED else RegenerateSecretState.TIMED_OUT
            }
        }

        fun updateHost(host: String) {
            _hostInput.value = host
            viewModelScope.launch(ioDispatcher) { settingsRepository.updateTransportHost(host) }
        }

        fun updatePort(portText: String) {
            _portInput.value = portText
            val port = portText.toIntOrNull()
            if (port == null) {
                _portError.value = "Port must be a number"
                return
            }
            if (port !in MIN_PORT..MAX_PORT) {
                _portError.value = "Port must be between $MIN_PORT and $MAX_PORT"
                return
            }
            _portError.value = null
            viewModelScope.launch(ioDispatcher) { settingsRepository.updateTransportPort(port) }
        }

        /**
         * Writes `enabled` *before* sending the start intent, not concurrently with it.
         * `TransportService.handleStart()` collects `transportConfig` and stops itself the moment
         * it observes `enabled == false` — a DataStore `Flow` re-emits the *current* value
         * immediately on collection, so if the write below were merely launched (fire-and-forget)
         * alongside `startService()` rather than awaited first, the service could start, begin
         * collecting, and observe the pre-write `enabled == false` before the write lands,
         * self-stopping within milliseconds of starting. Observed directly against a real device
         * during M3 verification, not a theoretical race.
         */
        fun start() {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateTransportEnabled(true)
                startService()
            }
        }

        fun stop() {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateTransportEnabled(false)
                stopService()
            }
        }

        private fun startService() {
            val intent =
                Intent(appContext, TransportService::class.java).apply { action = TransportService.ACTION_START }
            appContext.startForegroundService(intent)
        }

        private fun stopService() {
            val intent =
                Intent(appContext, TransportService::class.java).apply { action = TransportService.ACTION_STOP }
            appContext.startService(intent)
        }

        companion object {
            private const val STOP_TIMEOUT_MS = 5000L
            private const val MIN_PORT = 1
            private const val MAX_PORT = 65535
        }
    }
