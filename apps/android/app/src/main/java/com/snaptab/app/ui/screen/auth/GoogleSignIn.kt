package com.snaptab.app.ui.screen.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.snaptab.app.R

/**
 * What came back from the Google account picker.
 *
 * Cancelled is deliberately not a failure. Dismissing the sheet is a normal thing to do,
 * and an error banner for it would be the app telling you off for changing your mind.
 */
sealed interface GoogleSignInOutcome {
    data class Token(val idToken: String) : GoogleSignInOutcome
    data object Cancelled : GoogleSignInOutcome
    data class Failed(val messageRes: Int) : GoogleSignInOutcome
}

/**
 * Asks Credential Manager for a Google id token.
 *
 * This is the whole of the platform-specific part, kept in one function so the rest of
 * sign-in deals in a token string and three outcomes rather than in Google's exception
 * hierarchy.
 *
 * `serverClientId` is the **Web** application client id, not the Android one. The Android
 * OAuth client is matched by package name and signing certificate and is never named in
 * code; the Web client id is what ends up as the token's `aud`, and it is what the API
 * checks against GOOGLE_CLIENT_IDS. Passing the Android client id here produces a token
 * the server rejects, which looks like a server bug and is not one.
 *
 * `setFilterByAuthorizedAccounts(false)` so a first-time user sees their accounts at all;
 * filtered to authorised accounts only, someone who has never used SnapTab gets an empty
 * sheet. `setAutoSelectEnabled(false)` because silently signing in the one account on the
 * device, with no tap, reads as the app having done something behind your back.
 */
suspend fun requestGoogleIdToken(context: Context, serverClientId: String): GoogleSignInOutcome {
    if (serverClientId.isBlank()) return GoogleSignInOutcome.Failed(R.string.google_not_configured)

    val option = GetGoogleIdOption.Builder()
        .setServerClientId(serverClientId)
        .setFilterByAuthorizedAccounts(false)
        .setAutoSelectEnabled(false)
        .build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(option)
        .build()

    return try {
        val response = CredentialManager.create(context).getCredential(context, request)
        val credential = response.credential
        if (
            credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            if (idToken.isBlank()) {
                GoogleSignInOutcome.Failed(R.string.google_no_token)
            } else {
                GoogleSignInOutcome.Token(idToken)
            }
        } else {
            // Some other credential type came back. Nothing else is requested, so this
            // means the provider answered something we did not ask for.
            GoogleSignInOutcome.Failed(R.string.google_no_token)
        }
    } catch (cancelled: GetCredentialCancellationException) {
        GoogleSignInOutcome.Cancelled
    } catch (none: NoCredentialException) {
        // No Google account on the device, or Play Services is missing or too old. The
        // one message that is genuinely actionable, so it gets its own string.
        GoogleSignInOutcome.Failed(R.string.google_no_account)
    } catch (parsing: GoogleIdTokenParsingException) {
        GoogleSignInOutcome.Failed(R.string.google_no_token)
    } catch (failure: GetCredentialException) {
        // The catch-all for the rest of the hierarchy, and it has to come last: the two
        // above are subclasses, so ordering them after this one would never be reached.
        //
        // A misconfigured OAuth client lands here — wrong package name, an unregistered
        // signing SHA-1, or the Android client missing altogether.
        GoogleSignInOutcome.Failed(R.string.google_failed)
    }
}
