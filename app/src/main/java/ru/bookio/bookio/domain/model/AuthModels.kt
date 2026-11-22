package ru.bookio.bookio.domain.model

import kotlin.time.Instant

enum class UserRole { STUDENT, LIBRARIAN }
enum class AccountStatus { PENDING, ACTIVE, REJECTED }

data class User(
    val id: String,
    val login: String,
    val lastName: String,
    val firstName: String,
    val middleName: String?,
    val grade: Int?,
    val classLetter: String?,
    val role: UserRole,
    val status: AccountStatus,
)

data class AuthSession(
    val accessToken: String,
    val expiresAt: Instant,
    val user: User,
)
