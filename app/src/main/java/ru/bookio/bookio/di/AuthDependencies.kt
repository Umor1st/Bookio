package ru.bookio.bookio.di

import ru.bookio.bookio.domain.repository.AuthRepository
import ru.bookio.bookio.domain.usecase.GetCurrentSession
import ru.bookio.bookio.domain.usecase.Login
import ru.bookio.bookio.domain.usecase.Logout
import ru.bookio.bookio.domain.usecase.ObserveAuthSession
import ru.bookio.bookio.domain.usecase.RegisterStudent

class AuthDependencies(repository: AuthRepository = FakeAuthRepository()) {
    val registerStudent = RegisterStudent(repository)
    val login = Login(repository)
    val logout = Logout(repository)
    val getCurrentSession = GetCurrentSession(repository)
    val observeAuthSession = ObserveAuthSession(repository)
}
