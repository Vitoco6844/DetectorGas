package cl.detector.gas

import java.util.Locale

private const val MAX_PASSWORD_FAILURES = 3
private const val WAIT_MS = 5 * 60 * 1_000L

internal data class LoginAttempts(val failures: Int = 0, val retryAt: Long = 0) {
    val locked: Boolean get() = failures > MAX_PASSWORD_FAILURES
    val passwordAttemptsRemaining: Int get() = (MAX_PASSWORD_FAILURES - failures).coerceAtLeast(0)

    fun canSignIn(now: Long): Boolean = !locked && now >= retryAt

    fun afterInvalidPassword(now: Long): LoginAttempts {
        if (!canSignIn(now)) return this
        val next = failures + 1
        return LoginAttempts(next, if (next == MAX_PASSWORD_FAILURES) now + WAIT_MS else 0)
    }

    fun blockedMessage(now: Long): String? = when {
        locked -> "El acceso a esta cuenta está bloqueado en este teléfono."
        now < retryAt -> {
            val minutes = (retryAt - now + 59_999) / 60_000
            "Espera ${if (minutes == 1L) "1 minuto" else "$minutes minutos"} para tu último intento."
        }
        else -> null
    }
}

internal class LoginGuard(
    private val read: (String) -> LoginAttempts,
    private val write: (String, LoginAttempts) -> Unit,
    private val remove: (String) -> Unit
) {
    fun state(email: String): LoginAttempts = read(key(email))

    fun recordInvalidPassword(email: String, now: Long): LoginAttempts {
        val key = key(email)
        val previous = read(key)
        val updated = previous.afterInvalidPassword(now)
        if (updated != previous) write(key, updated)
        return updated
    }

    fun clear(email: String) = remove(key(email))

    private fun key(email: String): String = email.trim().lowercase(Locale.ROOT)
}

internal fun countsAsInvalidPassword(code: String): Boolean = when (code) {
    "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS" -> true
    else -> false
}
