package com.danielealbano.androidremotecontrolmcp.data.repository

import javax.inject.Inject

/**
 * DataStore-backed [SettingsRepository]. Every member is delegated to its feature slice
 * ([EventChannelSettingsImpl], [TransportSettingsImpl], [ConnectorUrlSettingsImpl]), which share
 * the same Preferences DataStore.
 */
class SettingsRepositoryImpl
    @Inject
    constructor(
        eventChannelSettings: EventChannelSettings,
        transportSettings: TransportSettings,
        connectorUrlSettings: ConnectorUrlSettings,
    ) : SettingsRepository,
        EventChannelSettings by eventChannelSettings,
        TransportSettings by transportSettings,
        ConnectorUrlSettings by connectorUrlSettings
