package cl.detector.gas

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

internal class LocalLoginException(message: String) : Exception(message)

internal class Accounts(private val context: Context) {
    val auth: FirebaseAuth? = FirebaseApp.getApps(context).firstOrNull()
        ?.let { FirebaseAuth.getInstance(it) }
    private val manager = CredentialManager.create(context)
    private val preferences = context.getSharedPreferences("login_attempts", Context.MODE_PRIVATE)
    private val loginGuard = LoginGuard(
        read = { email ->
            LoginAttempts(preferences.getInt("failures:$email", 0), preferences.getLong("retry:$email", 0))
        },
        write = { email, state ->
            if (!preferences.edit()
                    .putInt("failures:$email", state.failures)
                    .putLong("retry:$email", state.retryAt)
                    .commit()
            ) throw LocalLoginException("No se pudo guardar el estado de los intentos en este teléfono.")
        },
        remove = { email ->
            preferences.edit().remove("failures:$email").remove("retry:$email").apply()
        }
    )

    suspend fun signIn(email: String, password: String) {
        checkLocalAccess(email)
        try {
            requireNotNull(auth).signInWithEmailAndPassword(email, password).await()
        } catch (error: FirebaseAuthInvalidCredentialsException) {
            if (!countsAsInvalidPassword(error.errorCode)) throw error
            val now = System.currentTimeMillis()
            val attempts = loginGuard.recordInvalidPassword(email, now)
            val remaining = attempts.passwordAttemptsRemaining
            val remainingText = if (remaining == 1) "Te queda 1 intento." else "Te quedan $remaining intentos."
            throw LocalLoginException(
                attempts.blockedMessage(now) ?: "Revisa el correo y la contraseña. $remainingText"
            )
        }
        loginGuard.clear(email)
    }

    suspend fun register(email: String, password: String) {
        checkLocalAccess(email)
        requireNotNull(auth).createUserWithEmailAndPassword(email, password).await()
        loginGuard.clear(email)
    }

    suspend fun recover(email: String) {
        requireNotNull(auth).sendPasswordResetEmail(email).await()
    }

    suspend fun google(activity: Activity) {
        val clientIdResource = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        check(clientIdResource != 0) { "Falta el cliente OAuth en Firebase" }
        val option = GetSignInWithGoogleOption.Builder(context.getString(clientIdResource)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = manager.getCredential(activity, request).credential
        check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        val googleAccount = GoogleIdTokenCredential.createFrom(credential.data)
        checkLocalAccess(googleAccount.id)
        val firebaseAuth = requireNotNull(auth)
        val result = firebaseAuth.signInWithCredential(
            GoogleAuthProvider.getCredential(googleAccount.idToken, null)
        ).await()
        val email = result.user?.email ?: googleAccount.id
        val blocked = localBlockMessage(email)
        if (blocked != null) {
            firebaseAuth.signOut()
            throw LocalLoginException(blocked)
        }
        loginGuard.clear(email)
    }

    fun localBlockMessage(email: String): String? =
        loginGuard.state(email).blockedMessage(System.currentTimeMillis())

    private fun checkLocalAccess(email: String) {
        localBlockMessage(email)?.let { throw LocalLoginException(it) }
    }

    suspend fun signOut() {
        auth?.signOut()
        manager.clearCredentialState(ClearCredentialStateRequest())
    }
}
