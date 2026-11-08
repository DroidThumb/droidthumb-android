package com.danielealbano.androidremotecontrolmcp.services.lockscreen

import android.app.KeyguardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the phone's lock screen is currently showing. */
interface LockStateProvider {
    fun isLocked(): Boolean
}

@Singleton
class LockStateProviderImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : LockStateProvider {
        override fun isLocked(): Boolean {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            return keyguardManager.isKeyguardLocked
        }
    }
