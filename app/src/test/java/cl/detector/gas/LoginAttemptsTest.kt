package cl.detector.gas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginAttemptsTest {
    private val now = 1_000_000L

    @Test fun thirdFailureStartsFiveMinuteWait() {
        val first = LoginAttempts().afterInvalidPassword(now)
        val second = first.afterInvalidPassword(now + 100)
        val third = second.afterInvalidPassword(now + 200)
        assertEquals(2, first.passwordAttemptsRemaining)
        assertEquals(1, second.passwordAttemptsRemaining)
        assertEquals(now + 300_200, third.retryAt)
        assertFalse(third.canSignIn(now + 300_199))
        assertTrue(third.canSignIn(now + 300_200))
    }

    @Test fun attemptsDuringWaitDoNotEscalateOrExtendIt() {
        val waiting = LoginAttempts(failures = 3, retryAt = now + 300_000)
        assertEquals(waiting, waiting.afterInvalidPassword(now + 299_999))
    }

    @Test fun failureAfterWaitBlocksIndefinitely() {
        val waiting = LoginAttempts(failures = 3, retryAt = now)
        val locked = waiting.afterInvalidPassword(now)
        assertTrue(locked.locked)
        assertFalse(locked.canSignIn(Long.MAX_VALUE))
        assertEquals(locked, locked.afterInvalidPassword(Long.MAX_VALUE))
    }

    @Test fun successfulAccessClearsStoredAttemptsForThatEmailOnly() {
        val states = mutableMapOf<String, LoginAttempts>()
        val guard = LoginGuard(
            read = { states[it] ?: LoginAttempts() },
            write = { email, state -> states[email] = state },
            remove = { states.remove(it) }
        )
        repeat(3) { guard.recordInvalidPassword(" Example@Example.com ", now) }
        guard.recordInvalidPassword("other@example.com", now)
        assertFalse(guard.state("example@example.com").canSignIn(now))
        guard.clear("EXAMPLE@example.com")
        assertEquals(3, guard.state("example@example.com").passwordAttemptsRemaining)
        assertEquals(2, guard.state("other@example.com").passwordAttemptsRemaining)
    }

    @Test fun countsOnlyErrorsConsistentWithWrongLoginCredentials() {
        assertTrue(countsAsInvalidPassword("ERROR_WRONG_PASSWORD"))
        assertTrue(countsAsInvalidPassword("ERROR_INVALID_CREDENTIAL"))
        assertTrue(countsAsInvalidPassword("ERROR_INVALID_LOGIN_CREDENTIALS"))
        assertFalse(countsAsInvalidPassword("ERROR_NETWORK_REQUEST_FAILED"))
        assertFalse(countsAsInvalidPassword("ERROR_TOO_MANY_REQUESTS"))
        assertFalse(countsAsInvalidPassword("ERROR_USER_DISABLED"))
        assertFalse(countsAsInvalidPassword("ERROR_USER_NOT_FOUND"))
    }
}
