package ru.bookio.bookio.data.remote

import kotlinx.serialization.Serializable
import kotlin.time.Instant
import ru.bookio.bookio.domain.model.AccountStatus
import ru.bookio.bookio.domain.model.AuthSession
import ru.bookio.bookio.domain.model.User
import ru.bookio.bookio.domain.model.UserRole

private val tokenPattern = Regex("[A-Za-z0-9_-]{43}")

@Serializable
internal class RegisterRequest(
    val login: String,
    val password: String,
    val lastName: String,
    val firstName: String,
    val middleName: String?,
    val grade: Int,
    val classLetter: String,
)

@Serializable
internal class LoginRequest(val login: String, val password: String)

@Serializable
internal class AuthResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresAt: String,
    val user: UserResponse,
) {
    fun toDomain(): AuthSession {
        require(tokenType == "Bearer" && accessToken.matches(tokenPattern))
        return AuthSession(accessToken, Instant.parse(expiresAt), user.toDomain())
    }
}

@Serializable
internal class UserResponse(
    val id: String,
    val login: String,
    val lastName: String,
    val firstName: String,
    val middleName: String?,
    val grade: Int?,
    val classLetter: String?,
    val role: String,
    val status: String,
) {
    fun toDomain() = User(
        id, login, lastName, firstName, middleName, grade, classLetter,
        UserRole.valueOf(role), AccountStatus.valueOf(status),
    )
}

@Serializable
internal class ErrorResponse(val error: ApiError) {
    @Serializable
    class ApiError(val code: String)
}
