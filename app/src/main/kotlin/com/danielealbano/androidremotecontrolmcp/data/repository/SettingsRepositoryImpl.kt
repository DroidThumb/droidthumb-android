package com.danielealbano.androidremotecontrolmcp.data.repository

import javax.inject.Inject

/**
 * DataStore-backed [SettingsRepository]. Every member is delegated to the [EventChannelSettings]
 * slice ([EventChannelSettingsImpl]), which shares the same Preferences DataStore.
 */
class SettingsRepositoryImpl
    @Inject
    constructor(
        eventChannelSettings: EventChannelSettings,
    ) : SettingsRepository,
        EventChannelSettings by eventChannelSettings
