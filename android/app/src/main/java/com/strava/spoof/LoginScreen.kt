package com.strava.spoof

import android.content.Context
import android.util.Patterns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(repo: AuthRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(0) } // 0 = Sign in, 1 = Sign up
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val emailValid = email.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val passwordValid = password.length >= 6
    val canSubmit = emailValid && passwordValid && !loading

    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Strava Spoof",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (tab == 0) "Sign in to continue" else "Create a new account",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0; error = null }, text = { Text("Sign in") })
                Tab(selected = tab == 1, onClick = { tab = 1; error = null }, text = { Text("Sign up") })
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it; error = null },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = email.isNotBlank() && !emailValid,
                supportingText = {
                    if (email.isNotBlank() && !emailValid) Text("Enter a valid email address.")
                },
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                isError = password.isNotBlank() && !passwordValid,
                supportingText = {
                    if (password.isNotBlank() && !passwordValid) Text("At least 6 characters.")
                },
            )

            Button(
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        val result = if (tab == 0) repo.signInWithEmail(email, password)
                        else repo.signUpWithEmail(email, password)
                        result.onFailure { error = it.message }
                        loading = false
                    }
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (tab == 0) "Sign in" else "Create account")
            }

            Row3OrDivider("or")

            OutlinedButton(
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        val outcome = runCatching {
                            val idToken = requestGoogleIdToken(context)
                            repo.signInWithGoogleIdToken(idToken).getOrThrow()
                        }
                        outcome.onFailure { error = it.message }
                        loading = false
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Continue with Google")
            }

            if (loading) {
                Spacer(Modifier.height(4.dp))
                CircularProgressIndicator()
            }

            if (error != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    LaunchedEffect(tab) {
        password = ""
        error = null
    }
}

@Composable
private fun Row3OrDivider(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            "  $label  ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

private suspend fun requestGoogleIdToken(context: Context): String {
    val webClientId = resolveWebClientId(context)
        ?: throw AuthError(
            "Google sign-in not configured. Add a Web OAuth client in Firebase " +
                "(Project settings → Your apps → Web) and re-download google-services.json."
        )
    val option = GetGoogleIdOption.Builder()
        .setServerClientId(webClientId)
        .setFilterByAuthorizedAccounts(false)
        .setAutoSelectEnabled(false)
        .build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

    val response = try {
        CredentialManager.create(context).getCredential(context, request)
    } catch (e: NoCredentialException) {
        throw AuthError("No Google account available on this device. Add one in Settings → Accounts.")
    } catch (e: GetCredentialException) {
        throw AuthError("Google sign-in cancelled or failed: ${e.message ?: e.type}")
    }

    val cred = response.credential
    if (cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        throw AuthError("Unexpected credential type: ${cred.type}")
    }
    return try {
        GoogleIdTokenCredential.createFrom(cred.data).idToken
    } catch (e: GoogleIdTokenParsingException) {
        throw AuthError("Failed to parse Google ID token: ${e.message}")
    }
}

private fun resolveWebClientId(context: Context): String? {
    val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    if (id == 0) return null
    val value = runCatching { context.getString(id) }.getOrNull() ?: return null
    return value.takeIf { it.isNotBlank() }
}
