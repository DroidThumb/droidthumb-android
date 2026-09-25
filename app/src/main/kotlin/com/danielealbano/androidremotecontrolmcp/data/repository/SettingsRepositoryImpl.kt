package com.danielealbano.androidremotecontrolmcp.data.repository

import javax.inject.Inject

/**
 * DataStore-backed [SettingsRepository]. Every member is delegated to its feature slice
 * ([EventChannelSettingsImpl], [TransportSettingsImpl]), which share the same Preferences
 * DataStore.
 */
class SettingsRepositoryImpl
    @Inject
    constructor(
        eventChannelSettings: EventChannelSettings,
        transportSettings: TransportSettings,
    ) : SettingsRepository,
        EventChannelSettings by eventChannelSettings,
        TransportSettings by transportSettings
