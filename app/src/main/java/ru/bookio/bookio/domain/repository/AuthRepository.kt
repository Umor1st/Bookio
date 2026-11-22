package ru.bookio.bookio.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ru.bookio.bookio.domain.model.AuthSession

class RegisterStudentParams(
    val login: String,
    val password: String,
    val lastName: String,
    val firstName: String,
    val middleName: String?,
    val grade: Int,
    val classLetter: String,
)

class LoginParams(val login: String, val password: String)

sealed interface AuthResult {
    data class Success(val session: AuthSession) : AuthResult
    data class Failure(val error: AuthError) : AuthResult
}

enum class AuthError {
    INVALID_DATA, LOGIN_ALREADY_EXISTS, INVALID_CREDENTIALS, TEMPORARY_FAILURE, UNKNOWN
}

interface AuthRepository {
    val session: StateFlow<AuthSession?>
    suspend fun registerStudent(params: RegisterStudentParams): AuthResult
    suspend fun login(params: LoginParams): AuthResult
    fun currentSession(): AuthSession?
    fun logout()
}
