package com.danielealbano.androidremotecontrolmcp.data.model

import java.time.LocalDate
import java.time.ZoneId

/** Shared "until tomorrow" deadline computation — both the in-app pause dialog
 *  ([com.danielealbano.androidremotecontrolmcp.ui.viewmodels.TransportViewModel]) and the
 *  notification's own pause actions
 *  ([com.danielealbano.androidremotecontrolmcp.services.transport.TransportPauseActionReceiver])
 *  must compute the identical deadline, so it lives here once rather than twice. */
object PauseDeadlines {
    /** The start of the next calendar day in the device's own timezone. */
    fun startOfNextLocalDayEpochMs(): Long =
        LocalDate
            .now()
            .plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
