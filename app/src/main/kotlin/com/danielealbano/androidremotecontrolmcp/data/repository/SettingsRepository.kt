package com.danielealbano.androidremotecontrolmcp.data.repository

/**
 * Repository for accessing and persisting application settings.
 *
 * This is the single access point for all application settings.
 * All DataStore access MUST go through this interface. UI, ViewModels,
 * and Services must not access DataStore directly.
 *
 * Settings live in per-feature slices this interface extends: [EventChannelSettings] (the Event
 * Channel, the only settings left after the demolition pass, docs/plans/demolition.md) and
 * [TransportSettings] (the M2 device WebSocket transport).
 */
interface SettingsRepository :
    EventChannelSettings,
    TransportSettings
