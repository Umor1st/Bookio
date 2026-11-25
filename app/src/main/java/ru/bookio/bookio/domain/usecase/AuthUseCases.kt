package ru.bookio.bookio.domain.usecase

import ru.bookio.bookio.domain.repository.AuthRepository
import ru.bookio.bookio.domain.repository.LoginParams
import ru.bookio.bookio.domain.repository.RegisterStudentParams

class RegisterStudent(private val repository: AuthRepository) {
    suspend operator fun invoke(params: RegisterStudentParams) = repository.registerStudent(params)
}

class Login(private val repository: AuthRepository) {
    suspend operator fun invoke(params: LoginParams) = repository.login(params)
}

class Logout(private val repository: AuthRepository) {
    suspend operator fun invoke() = repository.logout()
}

class RestoreSession(private val repository: AuthRepository) {
    suspend operator fun invoke() = repository.restoreSession()
}

class GetCurrentSession(private val repository: AuthRepository) {
    operator fun invoke() = repository.currentSession()
}

class ObserveAuthSession(private val repository: AuthRepository) {
    operator fun invoke() = repository.session
}
