package com.snaptab.app.core

import javax.inject.Qualifier
import kotlin.annotation.AnnotationRetention.RUNTIME

/**
 * Dispatchers are injected rather than referenced as `Dispatchers.IO` directly, so a
 * test can hand a ViewModel a deterministic one.
 */
@Qualifier
@Retention(RUNTIME)
annotation class IoDispatcher

@Qualifier
@Retention(RUNTIME)
annotation class DefaultDispatcher
