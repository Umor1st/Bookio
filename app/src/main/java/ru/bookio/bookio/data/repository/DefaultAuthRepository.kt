package ru.bookio.bookio.data.repository

import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import ru.bookio.bookio.data.local.StoredToken
import ru.bookio.bookio.data.local.TokenStore
import ru.bookio.bookio.data.remote.AuthRemoteDataSource
import ru.bookio.bookio.data.remote.RemoteAuthFailure
import ru.bookio.bookio.domain.model.AuthSession
import ru.bookio.bookio.domain.repository.AuthError
import ru.bookio.bookio.domain.repository.AuthRepository
import ru.bookio.bookio.domain.repository.AuthResult
import ru.bookio.bookio.domain.repository.LoginParams
import ru.bookio.bookio.domain.repository.LogoutResult
import ru.bookio.bookio.domain.repository.RegisterStudentParams
import ru.bookio.bookio.domain.repository.RestoreSessionResult

internal class DefaultAuthRepository(
    private val remote: AuthRemoteDataSource,
    private val tokens: TokenStore,
    private val clock: Clock = Clock.System,
) : AuthRepository {
    private val mutex = Mutex()
    // Restore tracks changes to the stored token. Sign-in only needs to respect logout.
    private var sessionRevision = 0L
    private var logoutRevision = 0L
    private val mutableSession = MutableStateFlow<AuthSession?>(null)
    override val session = mutableSession.asStateFlow()

    override fun currentSession() = session.value

    override suspend fun registerStudent(params: RegisterStudentParams): AuthResult =
        authenticate { remote.register(params) }

    override suspend fun login(params: LoginParams): AuthResult = authenticate { remote.login(params) }

    private suspend fun authenticate(request: suspend () -> AuthSession): AuthResult {
        val revision = mutex.withLock { logoutRevision }
        try {
            val signedIn = request()
            return mutex.withLock {
                if (revision != logoutRevision) return@withLock AuthResult.Failure(AuthError.TEMPORARY_FAILURE)
                if (signedIn.expiresAt <= clock.now()) return@withLock AuthResult.Failure(AuthError.UNKNOWN)
                withContext(NonCancellable) {
                    tokens.write(StoredToken(signedIn.accessToken, signedIn.expiresAt))
                    sessionRevision++
                    mutableSession.value = signedIn
                }
                AuthResult.Success(signedIn)
            }
        } catch (failure: RemoteAuthFailure) {
            return AuthResult.Failure(failure.toDomain())
        } catch (_: IOException) {
            return AuthResult.Failure(AuthError.TEMPORARY_FAILURE)
        }
    }

    override suspend fun restoreSession(): RestoreSessionResult {
        try {
            val (saved, revision) = mutex.withLock {
                val token = tokens.read()
                if (token == null) {
                    sessionRevision++
                    mutableSession.value = null
                    return RestoreSessionResult.NoSession
                }
                if (token.expiresAt <= clock.now()) return forgetSession()
                token to sessionRevision
            }

            val response = try {
                Result.success(remote.me(saved.accessToken))
            } catch (failure: RemoteAuthFailure) {
                Result.failure(failure)
            }

            return mutex.withLock {
                if (revision != sessionRevision) {
                    return@withLock currentSession()?.let { RestoreSessionResult.Restored(it) }
                        ?: RestoreSessionResult.NoSession
                }
                val failure = response.exceptionOrNull()
                if (failure is RemoteAuthFailure && failure.httpStatus == 401 && failure.apiCode == "INVALID_SESSION") {
                    return@withLock forgetSession()
                }
                val user = response.getOrNull() ?: return@withLock RestoreSessionResult.RetryableFailure
                if (saved.expiresAt <= clock.now()) return@withLock forgetSession()
                val restored = AuthSession(saved.accessToken, saved.expiresAt, user)
                mutableSession.value = restored
                RestoreSessionResult.Restored(restored)
            }
        } catch (_: IOException) {
            return RestoreSessionResult.RetryableFailure
        }
    }

    private suspend fun forgetSession(): RestoreSessionResult = withContext(NonCancellable) {
        sessionRevision++
        mutableSession.value = null
        tokens.clear()
        RestoreSessionResult.NoSession
    }

    override suspend fun logout(): LogoutResult {
        val token = try {
            withContext(NonCancellable) {
                mutex.withLock {
                    val current = currentSession()?.accessToken ?: try {
                        tokens.read()?.accessToken
                    } catch (_: IOException) {
                        null
                    }
                    tokens.clear()
                    logoutRevision++
                    sessionRevision++
                    mutableSession.value = null
                    current
                }
            }
        } catch (_: IOException) {
            return LogoutResult.LOCAL_STORAGE_FAILURE
        }
        if (token != null) {
            withTimeoutOrNull(3_000) {
                try {
                    remote.logout(token)
                } catch (_: RemoteAuthFailure) {
                    // The local session has already been removed.
                }
            }
        }
        return LogoutResult.SUCCESS
    }

    private fun RemoteAuthFailure.toDomain(): AuthError = when {
        isTemporary -> AuthError.TEMPORARY_FAILURE
        httpStatus !in 400..499 -> AuthError.UNKNOWN
        apiCode == "INVALID_DATA" -> AuthError.INVALID_DATA
        apiCode == "LOGIN_ALREADY_EXISTS" -> AuthError.LOGIN_ALREADY_EXISTS
        apiCode == "INVALID_CREDENTIALS" -> AuthError.INVALID_CREDENTIALS
        else -> AuthError.UNKNOWN
    }
}
