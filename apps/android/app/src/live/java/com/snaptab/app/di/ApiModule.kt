package com.snaptab.app.di

import com.snaptab.app.core.StartupTask
import com.snaptab.app.data.remote.SnapTabApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * The live flavour: the API is Retrofit talking to the real server.
 */
@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    @Singleton
    fun api(retrofit: Retrofit): SnapTabApi = retrofit.create(SnapTabApi::class.java)

    @Provides
    @Singleton
    fun startupTask(): StartupTask = StartupTask.None
}
