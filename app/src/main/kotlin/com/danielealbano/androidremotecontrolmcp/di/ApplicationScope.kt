package com.danielealbano.androidremotecontrolmcp.di

import javax.inject.Qualifier

/** Qualifier for a process-lifetime [kotlinx.coroutines.CoroutineScope] - lets a singleton's own
 *  background coroutines (timers, etc.) be swapped for a test's `backgroundScope` in unit tests
 *  without the singleton hardcoding `Dispatchers.Default` itself. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
