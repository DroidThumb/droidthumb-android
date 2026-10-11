package com.danielealbano.androidremotecontrolmcp.data.repository

import javax.inject.Inject

/**
 * DataStore-backed [SettingsRepository]. Every member is delegated to its feature slice
 * ([TransportSettingsImpl], [ConnectorUrlSettingsImpl], [AccountSettingsImpl],
 * [PauseSettingsImpl], [ControlBarSettingsImpl]), which share the same Preferences DataStore.
 */
class SettingsRepositoryImpl
    @Inject
    constructor(
        transportSettings: TransportSettings,
        connectorUrlSettings: ConnectorUrlSettings,
        accountSettings: AccountSettings,
        pauseSettings: PauseSettings,
        controlBarSettings: ControlBarSettings,
    ) : SettingsRepository,
        TransportSettings by transportSettings,
        ConnectorUrlSettings by connectorUrlSettings,
        AccountSettings by accountSettings,
        PauseSettings by pauseSettings,
        ControlBarSettings by controlBarSettings
