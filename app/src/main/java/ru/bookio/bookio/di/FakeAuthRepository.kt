package ru.bookio.bookio.di

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant
import kotlin.time.Duration.Companion.days
import ru.bookio.bookio.domain.model.AccountStatus
import ru.bookio.bookio.domain.model.AuthSession
import ru.bookio.bookio.domain.model.User
import ru.bookio.bookio.domain.model.UserRole
import ru.bookio.bookio.domain.repository.AuthError
import ru.bookio.bookio.domain.repository.AuthRepository
import ru.bookio.bookio.domain.repository.AuthResult
import ru.bookio.bookio.domain.repository.LoginParams
import ru.bookio.bookio.domain.repository.LogoutResult
import ru.bookio.bookio.domain.repository.RestoreSessionResult
import ru.bookio.bookio.domain.repository.RegisterStudentParams

class FakeAuthRepository(
    users: List<User> = defaultFakeUsers(),
    private val issuedAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    var nextError: AuthError? = null,
) : AuthRepository {
    private val users = users.toMutableList()
    private val mutableSession = MutableStateFlow<AuthSession?>(null)
    private var nextId = 1L
    override val session: StateFlow<AuthSession?> = mutableSession.asStateFlow()

    override suspend fun registerStudent(params: RegisterStudentParams): AuthResult {
        nextError?.let { nextError = null; return AuthResult.Failure(it) }
        if (!validLogin(params.login) || params.password.length < 8 ||
            params.lastName.isBlank() || params.lastName.trim().length > 100 ||
            params.firstName.isBlank() || params.firstName.trim().length > 100 ||
            (params.middleName?.trim()?.length ?: 0) > 100 || params.grade !in 1..11 ||
            params.classLetter.isBlank() || params.classLetter.trim().length > 5
        ) return AuthResult.Failure(AuthError.INVALID_DATA)
        val existing = users.indexOfFirst { it.login.equals(params.login, ignoreCase = true) }
        if (existing >= 0 && users[existing].status != AccountStatus.REJECTED) {
            return AuthResult.Failure(AuthError.LOGIN_ALREADY_EXISTS)
        }
        val id = if (existing >= 0) users[existing].id else newId()
        val user = User(id, params.login.lowercase(), params.lastName.trim(), params.firstName.trim(),
            params.middleName?.trim(), params.grade, params.classLetter.trim(), UserRole.STUDENT, AccountStatus.PENDING)
        if (existing >= 0) users[existing] = user else users.add(user)
        return createSession(user)
    }

    override suspend fun login(params: LoginParams): AuthResult {
        nextError?.let { nextError = null; return AuthResult.Failure(it) }
        if (!validLogin(params.login)) return AuthResult.Failure(AuthError.INVALID_DATA)
        val user = users.firstOrNull { it.login.equals(params.login, ignoreCase = true) }
        if (user == null || params.password != FAKE_PASSWORD) return AuthResult.Failure(AuthError.INVALID_CREDENTIALS)
        return createSession(user)
    }

    override fun currentSession(): AuthSession? = mutableSession.value
    override suspend fun restoreSession(): RestoreSessionResult =
        currentSession()?.let { RestoreSessionResult.Restored(it) } ?: RestoreSessionResult.NoSession

    override suspend fun logout(): LogoutResult {
        mutableSession.value = null
        return LogoutResult.SUCCESS
    }

    private fun createSession(user: User): AuthResult {
        val session = AuthSession("fake-token-${user.id}", issuedAt + 30.days, user)
        mutableSession.value = session
        return AuthResult.Success(session)
    }

    private fun newId(): String {
        while (true) {
            val id = "00000000-0000-4000-8000-" + (nextId++).toString().padStart(12, '0')
            if (users.none { it.id == id }) return id
        }
    }

    private fun validLogin(login: String) = login.length in 3..32 && login.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' || it == '_'
    }

    companion object {
        const val FAKE_PASSWORD = "fake-password"
    }
}

fun defaultFakeUsers(): List<User> = listOf(
    User("10000000-0000-4000-8000-000000000001", "pending", "Иванов", "Иван", "Иванович", 8, "А", UserRole.STUDENT, AccountStatus.PENDING),
    User("10000000-0000-4000-8000-000000000002", "student", "Петрова", "Анна", null, 9, "Б", UserRole.STUDENT, AccountStatus.ACTIVE),
    User("10000000-0000-4000-8000-000000000003", "rejected", "Сидоров", "Олег", null, 7, "В", UserRole.STUDENT, AccountStatus.REJECTED),
    User("10000000-0000-4000-8000-000000000004", "librarian", "Смирнова", "Мария", null, null, null, UserRole.LIBRARIAN, AccountStatus.ACTIVE),
)
