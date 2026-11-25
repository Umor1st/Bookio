package ru.bookio.bookio.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Clock
import ru.bookio.bookio.BuildConfig
import ru.bookio.bookio.di.AndroidAuthDependencies
import ru.bookio.bookio.domain.model.AccountStatus
import ru.bookio.bookio.domain.model.UserRole
import ru.bookio.bookio.domain.repository.AuthResult
import ru.bookio.bookio.domain.repository.LogoutResult
import ru.bookio.bookio.domain.repository.RegisterStudentParams
import ru.bookio.bookio.domain.repository.RestoreSessionResult

@RunWith(AndroidJUnit4::class)
class LocalAuthBackendTest {
    @Test
    fun registersRestoresAndLogsOutAgainstLocalBackend() = runBlocking {
        assertTrue(
            "This test requires Debug and the local Backend through adb reverse",
            BuildConfig.DEBUG && BuildConfig.API_ORIGIN == "http://127.0.0.1:8080",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val auth = AndroidAuthDependencies.get(context)
        try {
            assertEquals(LogoutResult.SUCCESS, auth.logout())

            val login = "smoke${System.currentTimeMillis()}${UUID.randomUUID().toString().take(8)}"
            val registration = auth.registerStudent(
                RegisterStudentParams(login, UUID.randomUUID().toString(), "Smoke", "Student", null, 8, "A"),
            )
            val registered = when (registration) {
                is AuthResult.Success -> registration.session
                is AuthResult.Failure -> throw AssertionError("Registration failed with ${registration.error}")
            }
            assertEquals(UserRole.STUDENT, registered.user.role)
            assertEquals(AccountStatus.PENDING, registered.user.status)
            assertEquals(login, registered.user.login)
            assertTrue("Registration must return an access token", registered.accessToken.isNotBlank())
            assertTrue("The session must expire in the future", registered.expiresAt > Clock.System.now())

            val restoration = auth.restoreSession()
            assertTrue("Restore must return a session from /auth/me", restoration is RestoreSessionResult.Restored)
            val restored = (restoration as RestoreSessionResult.Restored).session
            assertEquals(registered.user, restored.user)
            assertTrue("Restored token must match the registered token", registered.accessToken == restored.accessToken)
            assertEquals(registered.expiresAt, restored.expiresAt)

            assertEquals(LogoutResult.SUCCESS, auth.logout())
            assertTrue("Logout must clear the local session", auth.getCurrentSession() == null)
            assertTrue("Restore after logout must return NoSession", auth.restoreSession() == RestoreSessionResult.NoSession)
        } finally {
            assertEquals("Test session cleanup must succeed", LogoutResult.SUCCESS, auth.logout())
        }
    }
}
