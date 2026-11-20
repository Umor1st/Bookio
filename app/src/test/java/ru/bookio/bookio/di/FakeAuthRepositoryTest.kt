package ru.bookio.bookio.di

import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
import org.junit.Assert.*
import org.junit.Test
import ru.bookio.bookio.domain.model.AccountStatus
import ru.bookio.bookio.domain.model.UserRole
import ru.bookio.bookio.domain.repository.AuthError
import ru.bookio.bookio.domain.repository.AuthResult
import ru.bookio.bookio.domain.repository.LoginParams
import ru.bookio.bookio.domain.repository.RegisterStudentParams

class FakeAuthRepositoryTest {
    private val repository = FakeAuthRepository(issuedAt = Instant.parse("2026-01-01T00:00:00Z"))
    private fun registration(login: String) = RegisterStudentParams(
        login, "new-password", "Иванов", "Иван", "Иванович", 8, "А"
    )
    private suspend fun login(name: String) = repository.login(LoginParams(name, FakeAuthRepository.FAKE_PASSWORD))
    private fun success(result: AuthResult) = (result as AuthResult.Success).session

    @Test fun registrationCreatesSessionAndStudentProfile() = runBlocking {
        val session = success(repository.registerStudent(registration("Ivanov8a")))
        assertEquals(AccountStatus.PENDING, session.user.status)
        assertEquals(UserRole.STUDENT, session.user.role)
        assertEquals("ivanov8a", session.user.login)
        assertEquals("Иванов", session.user.lastName)
        assertEquals("Иван", session.user.firstName)
        assertEquals("Иванович", session.user.middleName)
        assertEquals(8, session.user.grade)
        assertEquals("А", session.user.classLetter)
        assertEquals(Instant.parse("2026-01-31T00:00:00Z"), session.expiresAt)
        assertEquals(session, repository.currentSession())
    }

    @Test fun loginSupportsAllStatusesAndIgnoresCase() = runBlocking {
        assertEquals(AccountStatus.PENDING, success(login("PENDING")).user.status)
        assertEquals(AccountStatus.ACTIVE, success(login("Student")).user.status)
        assertEquals(AccountStatus.REJECTED, success(login("REJECTED")).user.status)
    }

    @Test fun conflictAndRejectedReregistration() = runBlocking {
        assertEquals(AuthResult.Failure(AuthError.LOGIN_ALREADY_EXISTS), repository.registerStudent(registration("STUDENT")))
        val previousId = success(login("rejected")).user.id
        val session = success(repository.registerStudent(registration("REJECTED")))
        assertEquals(previousId, session.user.id)
        assertEquals(AccountStatus.PENDING, session.user.status)
        assertEquals("Иванов", session.user.lastName)
    }

    @Test fun logoutClearsObservableSession() = runBlocking {
        val observed = repository.session
        val session = success(login("student"))
        assertEquals(session, observed.value)
        repository.logout()
        assertNull(repository.currentSession())
        assertNull(observed.value)
    }

    @Test fun librarianHasNoClass() = runBlocking {
        val user = success(login("librarian")).user
        assertEquals(UserRole.LIBRARIAN, user.role)
        assertNull(user.grade)
        assertNull(user.classLetter)
    }

    @Test fun errorsAreDeterministicAndDoNotChangeSession() = runBlocking {
        val session = success(login("student"))
        assertEquals(AuthResult.Failure(AuthError.INVALID_CREDENTIALS), repository.login(LoginParams("student", "wrong")))
        assertEquals(AuthResult.Failure(AuthError.INVALID_DATA), repository.registerStudent(registration("bad login")))
        repository.nextError = AuthError.TEMPORARY_FAILURE
        assertEquals(AuthResult.Failure(AuthError.TEMPORARY_FAILURE), login("student"))
        assertEquals(session, repository.currentSession())
        assertEquals(session, success(login("student")))
    }
}
