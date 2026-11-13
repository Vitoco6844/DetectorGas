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
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

internal class Accounts(private val context: Context) {
    val auth: FirebaseAuth? = FirebaseApp.getApps(context).firstOrNull()
        ?.let { FirebaseAuth.getInstance(it) }
    private val manager = CredentialManager.create(context)

    suspend fun signIn(email: String, password: String) {
        requireNotNull(auth).signInWithEmailAndPassword(email, password).await()
    }

    suspend fun register(email: String, password: String) {
        requireNotNull(auth).createUserWithEmailAndPassword(email, password).await()
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
        val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
        requireNotNull(auth).signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
    }

    suspend fun signOut() {
        auth?.signOut()
        manager.clearCredentialState(ClearCredentialStateRequest())
    }
}
