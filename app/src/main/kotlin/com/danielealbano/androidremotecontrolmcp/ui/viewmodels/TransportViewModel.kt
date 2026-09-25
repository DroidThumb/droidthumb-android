package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.TransportConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
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
        @ApplicationContext private val appContext: Context,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val transportConfig: StateFlow<TransportConfig> =
            settingsRepository.transportConfig
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TransportConfig())

        val transportStatus: StateFlow<TransportStatus> = TransportService.serviceStatus

        private val _hostInput = MutableStateFlow("")
        val hostInput: StateFlow<String> = _hostInput.asStateFlow()
        private val _portInput = MutableStateFlow(TransportConfig.DEFAULT_PORT.toString())
        val portInput: StateFlow<String> = _portInput.asStateFlow()
        private val _portError = MutableStateFlow<String?>(null)
        val portError: StateFlow<String?> = _portError.asStateFlow()

        init {
            viewModelScope.launch {
                transportConfig.collect { config ->
                    _hostInput.value = config.host
                    _portInput.value = config.port.toString()
                }
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

        fun start() {
            viewModelScope.launch(ioDispatcher) { settingsRepository.updateTransportEnabled(true) }
            startService()
        }

        fun stop() {
            viewModelScope.launch(ioDispatcher) { settingsRepository.updateTransportEnabled(false) }
            stopService()
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
