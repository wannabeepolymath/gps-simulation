package com.strava.spoof

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

sealed class AuthState {
    data object Loading : AuthState()
    data object SignedOut : AuthState()
    data class SignedIn(val uid: String, val email: String?, val displayName: String?) : AuthState()
}

class AuthRepository(private val auth: FirebaseAuth = FirebaseAuth.getInstance()) {

    private val _state = MutableStateFlow<AuthState>(toState(auth.currentUser))
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        auth.addAuthStateListener { fa -> _state.value = toState(fa.currentUser) }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> = runCatching {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
        Unit
    }.mapError()

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> = runCatching {
        auth.createUserWithEmailAndPassword(email.trim(), password).await()
        Unit
    }.mapError()

    suspend fun signInWithGoogleIdToken(idToken: String): Result<Unit> = runCatching {
        val cred = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(cred).await()
        Unit
    }.mapError()

    fun signOut() {
        auth.signOut()
    }

    private fun toState(user: com.google.firebase.auth.FirebaseUser?): AuthState =
        if (user == null) AuthState.SignedOut
        else AuthState.SignedIn(user.uid, user.email, user.displayName)

    private fun <T> Result<T>.mapError(): Result<T> = recoverCatching { e ->
        val msg = when (e) {
            is FirebaseAuthException -> humanize(e)
            else -> e.message ?: "Authentication failed"
        }
        throw AuthError(msg)
    }

    private fun humanize(e: FirebaseAuthException): String = when (e.errorCode) {
        "ERROR_INVALID_EMAIL" -> "That email address looks invalid."
        "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL" -> "Wrong email or password."
        "ERROR_USER_NOT_FOUND" -> "No account exists for that email."
        "ERROR_EMAIL_ALREADY_IN_USE" -> "An account already exists for that email."
        "ERROR_WEAK_PASSWORD" -> "Password must be at least 6 characters."
        "ERROR_NETWORK_REQUEST_FAILED" -> "Network error — check your connection."
        "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Wait a bit and try again."
        else -> e.message ?: "Authentication failed (${e.errorCode})"
    }
}

class AuthError(message: String) : Exception(message)
