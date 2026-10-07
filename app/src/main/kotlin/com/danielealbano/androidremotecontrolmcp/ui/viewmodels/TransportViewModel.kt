package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.PauseDeadlines
import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.transport.TransportAutoStart
import com.danielealbano.androidremotecontrolmcp.services.transport.TransportService
import com.danielealbano.androidremotecontrolmcp.services.transport.TransportStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransportViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val transportAutoStart: TransportAutoStart,
        @ApplicationContext private val appContext: Context,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val transportStatus: StateFlow<TransportStatus> = TransportService.serviceStatus

        /** The only user control over the transport (design doc §8.8 revision) — it starts on its
         *  own once signed in and accessible, and the UI only ever lets the owner pause/resume it. */
        val pauseState: StateFlow<PauseState> =
            settingsRepository.pauseState
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PauseState())

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
            private const val ONE_HOUR_MS = 60 * 60 * 1000L
        }
    }
