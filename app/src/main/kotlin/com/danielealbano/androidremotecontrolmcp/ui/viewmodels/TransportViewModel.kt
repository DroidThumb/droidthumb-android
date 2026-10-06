package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.PauseDeadlines
import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import com.danielealbano.androidremotecontrolmcp.data.model.TransportConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.transport.DeviceTransportClient
import com.danielealbano.androidremotecontrolmcp.services.transport.TransportAutoStart
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
        private val transportAutoStart: TransportAutoStart,
        @ApplicationContext private val appContext: Context,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        enum class RegenerateSecretState { IDLE, IN_PROGRESS, SUCCEEDED, TIMED_OUT }

        val transportConfig: StateFlow<TransportConfig> =
            settingsRepository.transportConfig
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TransportConfig())

        val transportStatus: StateFlow<TransportStatus> = TransportService.serviceStatus

        /** The only user control over the transport (design doc §8.8 revision) — it starts on its
         *  own once signed in and accessible, and the UI only ever lets the owner pause/resume it. */
        val pauseState: StateFlow<PauseState> =
            settingsRepository.pauseState
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PauseState())

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
                _regenerateState.value =
                    if (succeeded) RegenerateSecretState.SUCCEEDED else RegenerateSecretState.TIMED_OUT
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

        fun pauseFor1Hour() = pauseUntil(System.currentTimeMillis() + ONE_HOUR_MS)

        fun pauseUntilTomorrow() = pauseUntil(PauseDeadlines.startOfNextLocalDayEpochMs())

        fun pauseIndefinitely() = pauseUntil(null)

        /** Resuming only clears [PauseSettings] - it does not by itself guarantee the service is
         *  running (it never was, if the owner paused before ever signing in). [TransportAutoStart]
         *  re-runs the same signed-in+accessible+not-paused check completing onboarding does, so a
         *  resume while those are already met starts the service exactly the way that does. */
        fun resume() {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.resume()
                transportAutoStart.maybeStart(appContext)
            }
        }

        private fun pauseUntil(resumeAtEpochMs: Long?) {
            viewModelScope.launch(ioDispatcher) { settingsRepository.pauseUntil(resumeAtEpochMs) }
        }

        companion object {
            private const val STOP_TIMEOUT_MS = 5000L
            private const val MIN_PORT = 1
            private const val MAX_PORT = 65535
            private const val ONE_HOUR_MS = 60 * 60 * 1000L
        }
    }
