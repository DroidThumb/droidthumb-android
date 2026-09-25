package com.danielealbano.androidremotecontrolmcp.di

import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Qualifier

/** Qualifier for the IO [CoroutineDispatcher]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
