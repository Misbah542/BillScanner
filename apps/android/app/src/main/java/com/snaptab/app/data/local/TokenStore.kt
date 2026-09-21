package com.snaptab.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tokenDataStore by preferencesDataStore(name = "snaptab_session")

/**
 * Holds the session and the handful of settings the app needs before it can talk to
 * the server.
 *
 * Tokens are excluded from backup (see res/xml/backup_rules.xml): restoring a backup
 * onto another device must not carry a live session with it.
 */
@Singleton
class TokenStore @Inject constructor(private val context: Context) {

    private object Keys {
        val accessToken = stringPreferencesKey("access_token")
        val refreshToken = stringPreferencesKey("refresh_token")
        val accessExpiresAt = longPreferencesKey("access_expires_at")
        val userId = stringPreferencesKey("user_id")
        val userName = stringPreferencesKey("user_name")
        val avatarUrl = stringPreferencesKey("avatar_url")
        val currency = stringPreferencesKey("currency")
        val installId = stringPreferencesKey("install_id")
        val smsEnabled = booleanPreferencesKey("sms_enabled")
        val keepAlertBodies = booleanPreferencesKey("keep_alert_bodies")
        val smsBackfilledAt = longPreferencesKey("sms_backfilled_at")
        val askedForSms = booleanPreferencesKey("asked_for_sms")
    }

    val isSignedIn: Flow<Boolean> =
        context.tokenDataStore.data.map { it[Keys.refreshToken]?.isNotBlank() == true }

    val userId: Flow<String?> = context.tokenDataStore.data.map { it[Keys.userId] }

    /**
     * Name and avatar, so the header can draw the right face on its first frame.
     *
     * The name was already being stored and had no Flow to read it by, which is why the
     * home screen passed null and got a question mark.
     */
    val userName: Flow<String?> = context.tokenDataStore.data.map { it[Keys.userName] }
    val avatarUrl: Flow<String?> = context.tokenDataStore.data.map { it[Keys.avatarUrl] }
    val currency: Flow<String> = context.tokenDataStore.data.map { it[Keys.currency] ?: "INR" }
    val smsEnabled: Flow<Boolean> = context.tokenDataStore.data.map { it[Keys.smsEnabled] ?: false }
    val keepAlertBodies: Flow<Boolean> =
        context.tokenDataStore.data.map { it[Keys.keepAlertBodies] ?: false }
    val askedForSms: Flow<Boolean> = context.tokenDataStore.data.map { it[Keys.askedForSms] ?: false }

    suspend fun accessToken(): String? = context.tokenDataStore.data.first()[Keys.accessToken]

    suspend fun refreshToken(): String? = context.tokenDataStore.data.first()[Keys.refreshToken]

    suspend fun currentUserId(): String? = context.tokenDataStore.data.first()[Keys.userId]

    suspend fun isSmsEnabledNow(): Boolean =
        context.tokenDataStore.data.first()[Keys.smsEnabled] ?: false

    suspend fun shouldSendAlertBodies(): Boolean =
        context.tokenDataStore.data.first()[Keys.keepAlertBodies] ?: false

    /**
     * A stable id for this installation, used to register the device without touching
     * any hardware identifier. Generated once and kept until the app is uninstalled.
     */
    suspend fun installId(): String {
        val existing = context.tokenDataStore.data.first()[Keys.installId]
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        context.tokenDataStore.edit { it[Keys.installId] = generated }
        return generated
    }

    suspend fun saveSession(
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Int,
        userId: String? = null,
        userName: String? = null,
        avatarUrl: String? = null,
        currency: String? = null
    ) {
        context.tokenDataStore.edit { prefs ->
            prefs[Keys.accessToken] = accessToken
            prefs[Keys.refreshToken] = refreshToken
            // A minute of slack, so a request in flight does not fail on a boundary.
            prefs[Keys.accessExpiresAt] =
                System.currentTimeMillis() + (expiresInSeconds - 60).coerceAtLeast(0) * 1000L
            userId?.let { prefs[Keys.userId] = it }
            userName?.let { prefs[Keys.userName] = it }
            avatarUrl?.let { prefs[Keys.avatarUrl] = it }
            currency?.let { prefs[Keys.currency] = it }
        }
    }

    /** Mirrors what /users/me returned, so the cached header matches the profile screen. */
    suspend fun saveProfile(name: String?, avatarUrl: String?) {
        context.tokenDataStore.edit { prefs ->
            name?.let { prefs[Keys.userName] = it }
            // Explicitly removable: clearing your picture has to clear the cached one too.
            if (avatarUrl.isNullOrBlank()) prefs.remove(Keys.avatarUrl) else prefs[Keys.avatarUrl] = avatarUrl
        }
    }

    suspend fun setSmsEnabled(enabled: Boolean) {
        context.tokenDataStore.edit { it[Keys.smsEnabled] = enabled }
    }

    suspend fun setKeepAlertBodies(keep: Boolean) {
        context.tokenDataStore.edit { it[Keys.keepAlertBodies] = keep }
    }

    suspend fun markAskedForSms() {
        context.tokenDataStore.edit { it[Keys.askedForSms] = true }
    }

    suspend fun markSmsBackfilled() {
        context.tokenDataStore.edit { it[Keys.smsBackfilledAt] = System.currentTimeMillis() }
    }

    suspend fun hasBackfilledSms(): Boolean =
        (context.tokenDataStore.data.first()[Keys.smsBackfilledAt] ?: 0L) > 0L

    /** Clears the session but keeps the install id, so re-signing in reuses the device row. */
    suspend fun clearSession() {
        context.tokenDataStore.edit { prefs ->
            prefs.remove(Keys.accessToken)
            prefs.remove(Keys.refreshToken)
            prefs.remove(Keys.accessExpiresAt)
            prefs.remove(Keys.userId)
            prefs.remove(Keys.userName)
            prefs.remove(Keys.avatarUrl)
            prefs.remove(Keys.smsBackfilledAt)
        }
    }
}
