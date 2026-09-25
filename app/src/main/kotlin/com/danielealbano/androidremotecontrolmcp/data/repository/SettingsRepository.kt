package com.danielealbano.androidremotecontrolmcp.data.repository

/**
 * Repository for accessing and persisting application settings.
 *
 * This is the single access point for all application settings.
 * All DataStore access MUST go through this interface. UI, ViewModels,
 * and Services must not access DataStore directly.
 *
 * The only settings left after the demolition pass (docs/plans/demolition.md) are the Event
 * Channel's, which live in the [EventChannelSettings] slice this interface extends.
 */
interface SettingsRepository : EventChannelSettings
