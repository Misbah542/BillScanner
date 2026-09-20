package com.snaptab.app.di

import android.content.Context
import androidx.room.Room
import com.snaptab.app.BuildConfig
import com.snaptab.app.core.DefaultDispatcher
import com.snaptab.app.core.IoDispatcher
import com.snaptab.app.data.local.*
import com.snaptab.app.data.remote.AuthInterceptor
import com.snaptab.app.data.remote.TokenAuthenticator
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        // The server omits nulls, and the app must survive a field it has not
        // heard of yet — that is what lets the API add a field without breaking
        // older installs.
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun loggingInterceptor(): HttpLoggingInterceptor = HttpLoggingInterceptor().apply {
        // BASIC in debug: method, URL, status and timing. Never BODY, which would put
        // tokens, amounts and bank details into logcat — the old NetworkModule set
        // Level.BODY unconditionally, in release builds too.
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
        redactHeader("Authorization")
    }

    @Provides
    @Singleton
    @BaseUrl
    fun baseUrl(): String = BuildConfig.API_BASE_URL

    @Provides
    @Singleton
    fun tokenAuthenticator(
        tokenStore: TokenStore,
        @BaseUrl baseUrl: Provider<String>,
        json: Json
    ): TokenAuthenticator = TokenAuthenticator(tokenStore, baseUrl, json)

    @Provides
    @Singleton
    fun okHttpClient(
        authInterceptor: AuthInterceptor,
        logging: HttpLoggingInterceptor,
        authenticator: TokenAuthenticator
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(logging)
        .authenticator(authenticator)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Generous, because this also carries receipt uploads on a bad connection.
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient, json: Json, @BaseUrl baseUrl: String): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            // kotlinx-serialization, matching the @Serializable models. The old project
            // annotated its models with @SerialName and then installed Gson, which
            // ignores those annotations — every renamed field deserialized as zero.
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SnapTabDatabase =
        Room.databaseBuilder(context, SnapTabDatabase::class.java, SnapTabDatabase.NAME)
            // The cache can always be refetched, so a schema change drops it rather than
            // shipping a migration for data the server owns.
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun expenseDao(db: SnapTabDatabase): ExpenseDao = db.expenses()
    @Provides fun alertDao(db: SnapTabDatabase): AlertDao = db.alerts()
    @Provides fun pendingAlertDao(db: SnapTabDatabase): PendingAlertDao = db.pendingAlerts()
    @Provides fun groupDao(db: SnapTabDatabase): GroupDao = db.groups()
    @Provides fun categoryDao(db: SnapTabDatabase): CategoryDao = db.categories()
    @Provides fun monthlySummaryDao(db: SnapTabDatabase): MonthlySummaryDao = db.monthlySummaries()

    @Provides
    @Singleton
    fun tokenStore(@ApplicationContext context: Context): TokenStore = TokenStore(context)

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BaseUrl
