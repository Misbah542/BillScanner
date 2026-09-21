package com.snaptab.app.di

import com.snaptab.app.core.StartupTask
import com.snaptab.app.data.local.TokenStore
import com.snaptab.app.data.remote.SnapTabApi
import com.snaptab.app.data.remote.fake.FakeSnapTabApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The demo flavour: the API is swapped for an in-memory fake, so the app needs no server,
 * no database and no network permission to be useful.
 *
 * The swap happens at the SnapTabApi boundary deliberately. Faking the repositories
 * instead would skip the very code most worth exercising — the DTO mapping, the Room
 * cache, the split arithmetic, the offline and error branches all still run exactly as
 * they do against the real server.
 */
@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    @Singleton
    fun fakeApi(): FakeSnapTabApi = FakeSnapTabApi()

    @Provides
    @Singleton
    fun api(fake: FakeSnapTabApi): SnapTabApi = fake

    /** Signs the demo user in on first launch, so the app opens straight onto content. */
    @Provides
    @Singleton
    fun startupTask(tokenStore: TokenStore): StartupTask = StartupTask {
        if (tokenStore.refreshToken().isNullOrBlank()) {
            tokenStore.saveSession(
                accessToken = "demo-access-token",
                refreshToken = "demo-refresh-token",
                expiresInSeconds = 60 * 60 * 24 * 365,
                userId = FakeSnapTabApi.ME_ID,
                userName = "Misbahul Haque",
                currency = "INR"
            )
            // Reading alerts is on in the demo so the inbox has something in it.
            tokenStore.setSmsEnabled(true)
        }
    }
}
