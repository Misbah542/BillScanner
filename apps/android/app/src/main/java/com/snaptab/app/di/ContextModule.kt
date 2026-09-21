package com.snaptab.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * A few classes take a plain `Context` rather than being annotated with
 * `@ApplicationContext` at every use site — the notifier, the rules loader, the token
 * store. This binds it once.
 */
@Module
@InstallIn(SingletonComponent::class)
object ContextModule {

    @Provides
    @Singleton
    fun context(@ApplicationContext context: Context): Context = context
}
