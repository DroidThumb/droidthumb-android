package com.danielealbano.androidremotecontrolmcp.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.danielealbano.androidremotecontrolmcp.data.repository.EventChannelSettings
import com.danielealbano.androidremotecontrolmcp.data.repository.EventChannelSettingsImpl
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepositoryImpl
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepositoryImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCacheImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProviderImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutorImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCacheImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputController
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputControllerImpl
import com.danielealbano.androidremotecontrolmcp.services.apps.AppManager
import com.danielealbano.androidremotecontrolmcp.services.apps.AppManagerImpl
import com.danielealbano.androidremotecontrolmcp.services.channel.EventDispatcher
import com.danielealbano.androidremotecontrolmcp.services.channel.EventDispatcherImpl
import com.danielealbano.androidremotecontrolmcp.services.intents.IntentDispatcher
import com.danielealbano.androidremotecontrolmcp.services.intents.IntentDispatcherImpl
import com.danielealbano.androidremotecontrolmcp.services.power.BatteryOptimizationManager
import com.danielealbano.androidremotecontrolmcp.services.power.BatteryOptimizationManagerImpl
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ApiLevelProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.DefaultApiLevelProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProviderImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

/** Extension property for creating the Preferences DataStore on [Context]. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /**
     * Provides the application-scoped [DataStore] for settings persistence.
     */
    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.settingsDataStore

    /**
     * Provides [Dispatchers.IO] for background work.
     */
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    /**
     * Binds [SettingsRepositoryImpl] as the implementation of [SettingsRepository].
     */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    /** Binds the event-channel settings slice that [SettingsRepositoryImpl] delegates to. */
    @Binds
    @Singleton
    abstract fun bindEventChannelSettings(impl: EventChannelSettingsImpl): EventChannelSettings

    /** Binds the disk-backed server log used by the in-app logs viewer. */
    @Binds
    @Singleton
    abstract fun bindServerLogRepository(impl: ServerLogRepositoryImpl): ServerLogRepository
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceModule {
    @Binds
    @Singleton
    abstract fun bindAccessibilityNodeCache(impl: AccessibilityNodeCacheImpl): AccessibilityNodeCache

    @Binds
    @Singleton
    abstract fun bindScreenStateSnapshotCache(impl: ScreenStateSnapshotCacheImpl): ScreenStateSnapshotCache

    @Binds
    @Singleton
    abstract fun bindApiLevelProvider(impl: DefaultApiLevelProvider): ApiLevelProvider

    @Binds
    @Singleton
    abstract fun bindTypeInputController(impl: TypeInputControllerImpl): TypeInputController

    @Binds
    @Singleton
    abstract fun bindActionExecutor(impl: ActionExecutorImpl): ActionExecutor

    @Binds
    @Singleton
    abstract fun bindAccessibilityServiceProvider(impl: AccessibilityServiceProviderImpl): AccessibilityServiceProvider

    @Binds
    @Singleton
    abstract fun bindScreenCaptureProvider(impl: ScreenCaptureProviderImpl): ScreenCaptureProvider

    @Binds
    @Singleton
    abstract fun bindAppManager(impl: AppManagerImpl): AppManager

    @Binds
    @Singleton
    abstract fun bindIntentDispatcher(impl: IntentDispatcherImpl): IntentDispatcher

    @Binds
    @Singleton
    abstract fun bindEventDispatcher(impl: EventDispatcherImpl): EventDispatcher

    @Binds
    @Singleton
    abstract fun bindBatteryOptimizationManager(impl: BatteryOptimizationManagerImpl): BatteryOptimizationManager
}
