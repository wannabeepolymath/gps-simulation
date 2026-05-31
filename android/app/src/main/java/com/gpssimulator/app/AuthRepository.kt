package com.gpssimulator.app

import android.content.Context
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

sealed class AuthState {
    data object Loading : AuthState()
    data object SignedOut : AuthState()
    data class SignedIn(val sub: String, val email: String?, val displayName: String?) : AuthState()
}

class AuthError(message: String) : Exception(message)

class AuthRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("auth", Context.MODE_PRIVATE)

    @Volatile private var cachedIdToken: String? = null
    @Volatile private var tokenAcquiredAtMs: Long = 0L

    private val _state = MutableStateFlow<AuthState>(restoreFromPrefs())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Interactive sign-in. Must be called with an Activity context for the picker UI. */
    suspend fun signIn(activityContext: Context) {
        applyCredential(requestGoogleIdToken(activityContext, allowUi = true))
    }

    /**
     * Silent refresh — uses Credential Manager auto-select to re-fetch an ID token
     * without UI. Throws AuthError if no authorized account is available.
     */
    suspend fun silentRefresh(): String {
        val cred = requestGoogleIdToken(appContext, allowUi = false)
        applyCredential(cred)
        return cred.idToken
    }

    /** Returns the cached ID token if we have one. Does NOT refresh. */
    fun currentIdToken(): String? = cachedIdToken

    /** Hard sign-out: clears local state and tells Credential Manager to forget us. */
    suspend fun signOut() {
        cachedIdToken = null
        tokenAcquiredAtMs = 0L
        prefs.edit().clear().apply()
        _state.value = AuthState.SignedOut
        runCatching {
            CredentialManager.create(appContext)
                .clearCredentialState(ClearCredentialStateRequest())
        }
    }

    private suspend fun requestGoogleIdToken(
        context: Context,
        allowUi: Boolean,
    ): GoogleIdTokenCredential {
        val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (webClientId.isBlank()) {
            throw AuthError(
                "Google sign-in not configured: GOOGLE_WEB_CLIENT_ID is empty. " +
                    "Set google.web.client.id in gradle.properties."
            )
        }
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(!allowUi)
            .setAutoSelectEnabled(!allowUi)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val response = try {
            CredentialManager.create(context).getCredential(context, request)
        } catch (e: NoCredentialException) {
            throw AuthError(
                if (allowUi) "No Google account available. Add one in Settings → Accounts."
                else "Silent sign-in failed — please sign in again."
            )
        } catch (e: GetCredentialException) {
            throw AuthError("Google sign-in cancelled or failed: ${e.message ?: e.type}")
        }

        val rawCred = response.credential
        if (rawCred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw AuthError("Unexpected credential type: ${rawCred.type}")
        }
        return try {
            GoogleIdTokenCredential.createFrom(rawCred.data)
        } catch (e: GoogleIdTokenParsingException) {
            throw AuthError("Failed to parse Google ID token: ${e.message}")
        }
    }

    private fun applyCredential(cred: GoogleIdTokenCredential) {
        val payload = decodeJwtPayload(cred.idToken)
        val sub = payload.optString("sub").ifBlank {
            throw AuthError("ID token missing 'sub' claim.")
        }
        val email = payload.optString("email", "").ifBlank { null }
        val name = cred.displayName ?: payload.optString("name", "").ifBlank { null }
        val expSec = payload.optLong("exp", 0L)
        val expMs = if (expSec > 0L) expSec * 1000L else 0L

        cachedIdToken = cred.idToken
        tokenAcquiredAtMs = System.currentTimeMillis()

        prefs.edit()
            .putString(KEY_SUB, sub)
            .putString(KEY_EMAIL, email)
            .putString(KEY_NAME, name)
            .putString(KEY_ID_TOKEN, cred.idToken)
            .putLong(KEY_ID_TOKEN_EXP_MS, expMs)
            .apply()

        _state.value = AuthState.SignedIn(sub, email, name)
    }

    private fun restoreFromPrefs(): AuthState {
        val sub = prefs.getString(KEY_SUB, null) ?: return AuthState.SignedOut
        val email = prefs.getString(KEY_EMAIL, null)
        val name = prefs.getString(KEY_NAME, null)

        // Reuse the previously-minted ID token if it still has runway. Skips the
        // Credential Manager → Play Services hop on cold start, which is the
        // dominant cost on the first API call. If the token has expired or the
        // server rejects it (401), ApiClient.call() falls back to silentRefresh.
        val storedToken = prefs.getString(KEY_ID_TOKEN, null)
        val expMs = prefs.getLong(KEY_ID_TOKEN_EXP_MS, 0L)
        if (storedToken != null && expMs > System.currentTimeMillis() + TOKEN_EXPIRY_BUFFER_MS) {
            cachedIdToken = storedToken
            tokenAcquiredAtMs = System.currentTimeMillis()
        }

        return AuthState.SignedIn(sub, email, name)
    }

    private fun decodeJwtPayload(jwt: String): JSONObject {
        val parts = jwt.split(".")
        if (parts.size < 2) throw AuthError("Malformed ID token.")
        val bytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    companion object {
        private const val KEY_SUB = "sub"
        private const val KEY_EMAIL = "email"
        private const val KEY_NAME = "name"
        private const val KEY_ID_TOKEN = "id_token"
        private const val KEY_ID_TOKEN_EXP_MS = "id_token_exp_ms"

        // Don't reuse a token with <60s of runway — avoids races where the token
        // expires mid-request and the server 401s after we've already started.
        private const val TOKEN_EXPIRY_BUFFER_MS = 60_000L
    }
}
