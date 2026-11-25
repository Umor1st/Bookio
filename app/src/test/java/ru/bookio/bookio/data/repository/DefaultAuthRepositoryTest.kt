package ru.bookio.bookio.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import ru.bookio.bookio.data.local.StoredToken
import ru.bookio.bookio.data.local.TokenStore
import ru.bookio.bookio.data.remote.AuthRemoteDataSource
import ru.bookio.bookio.data.remote.RemoteAuthFailure
import ru.bookio.bookio.data.remote.configureBookioClient
import ru.bookio.bookio.domain.model.AccountStatus
import ru.bookio.bookio.domain.model.AuthSession
import ru.bookio.bookio.domain.model.User
import ru.bookio.bookio.domain.model.UserRole
import ru.bookio.bookio.domain.repository.AuthError
import ru.bookio.bookio.domain.repository.AuthResult
import ru.bookio.bookio.domain.repository.LoginParams
import ru.bookio.bookio.domain.repository.LogoutResult
import ru.bookio.bookio.domain.repository.RegisterStudentParams
import ru.bookio.bookio.domain.repository.RestoreSessionResult

class DefaultAuthRepositoryTest {
    private val now = Instant.parse("2026-11-25T12:00:00Z")
    private val expires = Instant.parse("2026-12-25T12:00:00.123456Z")
    private val saved = StoredToken(TOKEN, expires)
    private val tokens = MemoryTokenStore()
    private val login = LoginParams("User.ID", PASSWORD)
    private val registration = RegisterStudentParams("User.ID", PASSWORD, " Иванов ", " Иван ", null, 8, " А ")
    private val librarian = User(
        ID, "user.id", "Иванов", "Иван", null, null, null, UserRole.LIBRARIAN, AccountStatus.ACTIVE,
    )
    private val student = librarian.copy(
        middleName = "Иванович", grade = 8, classLetter = "А", role = UserRole.STUDENT, status = AccountStatus.PENDING,
    )

    @Test fun registerAndLoginSendExactJsonAndPublishSessions() = runBlocking {
        withRepository({ request ->
            val (path, body, response) = when (request.url.encodedPath) {
                "/api/v1/auth/register" -> Triple(
                    "register",
                    """{"login":"User.ID","password":"$PASSWORD","lastName":" Иванов ","firstName":" Иван ","middleName":null,"grade":8,"classLetter":" А "}""",
                    json(authResponse(student), HttpStatusCode.Created),
                )
                "/api/v1/auth/login" -> Triple(
                    "login", """{"login":"User.ID","password":"$PASSWORD"}""", json(authResponse(librarian)),
                )
                else -> error("Unexpected auth endpoint")
            }
            checkRequest(request, path, HttpMethod.Post)
            val content = request.body as OutgoingContent.ByteArrayContent
            assertEquals(ContentType.Application.Json, content.contentType?.withoutParameters())
            assertEquals(Json.parseToJsonElement(body), Json.parseToJsonElement(content.bytes().decodeToString()))
            response
        }) { repository ->
            for (expected in listOf(student, librarian)) {
                val result = if (expected == student) repository.registerStudent(registration) else repository.login(login)
                val session = AuthSession(TOKEN, expires, expected)
                assertEquals(AuthResult.Success(session), result)
                assertEquals(session, repository.session.value)
                assertEquals(TOKEN, tokens.value?.accessToken)
                assertEquals(expires, tokens.value?.expiresAt)
            }
            assertEquals(2, tokens.writes)
        }
    }

    @Test fun authErrorCodesAreIndependentOfMessageAndKeepExistingSession() = runBlocking {
        val failures = listOf(
            Triple(HttpStatusCode.BadRequest, "INVALID_DATA", AuthError.INVALID_DATA),
            Triple(HttpStatusCode.Conflict, "LOGIN_ALREADY_EXISTS", AuthError.LOGIN_ALREADY_EXISTS),
            Triple(HttpStatusCode.Unauthorized, "INVALID_CREDENTIALS", AuthError.INVALID_CREDENTIALS),
            Triple(HttpStatusCode.TooManyRequests, "RATE_LIMITED", AuthError.TEMPORARY_FAILURE),
            Triple(HttpStatusCode.InternalServerError, "INTERNAL_ERROR", AuthError.TEMPORARY_FAILURE),
            Triple(HttpStatusCode.Unauthorized, "INVALID_SESSION", AuthError.UNKNOWN),
            Triple(HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", AuthError.UNKNOWN),
            Triple(HttpStatusCode.BadRequest, "UNRECOGNIZED_CODE", AuthError.UNKNOWN),
        )
        var next = 0
        withRepository({ request ->
            if (next == 0) {
                next++
                json(authResponse(librarian))
            } else {
                val (status, code) = failures[next++ - 1]
                assertNull(request.headers[HttpHeaders.Authorization])
                json(apiError(code), status)
            }
        }) { repository ->
            val existing = (repository.login(login) as AuthResult.Success).session
            for ((status, _, expected) in failures) {
                val result = if (status == HttpStatusCode.Conflict) repository.registerStudent(registration)
                    else repository.login(login)
                assertEquals(AuthResult.Failure(expected), result)
                assertSame(existing, repository.session.value)
                assertEquals(TOKEN, tokens.value?.accessToken)
            }
            assertEquals(1, tokens.writes)
            assertEquals(failures.size + 1, next)
        }
    }

    @Test fun networkFailuresAreTemporaryAndMalformedRepliesAreUnknown() = runBlocking {
        var requests = 0
        withRepository({
            when (++requests) {
                1 -> json(authResponse(librarian))
                2 -> throw IOException("transport failure")
                3 -> json("not JSON", HttpStatusCode.BadGateway)
                4 -> json("not JSON", HttpStatusCode.BadRequest)
                5 -> json("not JSON")
                6 -> json(authResponse(librarian).replace(expires.toString(), "not-an-instant"))
                7 -> json(authResponse(librarian), HttpStatusCode.Created)
                8 -> json(authResponse(librarian).replace("LIBRARIAN", "NEW_ROLE"))
                else -> error("Unexpected extra request")
            }
        }) { repository ->
            val existing = repository.login(login)
            assertEquals(AuthResult.Success(AuthSession(TOKEN, expires, librarian)), existing)
            val errors = List(2) { AuthError.TEMPORARY_FAILURE } + List(5) { AuthError.UNKNOWN }
            for (expected in errors) {
                assertEquals(AuthResult.Failure(expected), repository.login(login))
                assertEquals((existing as AuthResult.Success).session, repository.session.value)
            }
            assertEquals(8, requests)
            assertEquals(1, tokens.writes)
        }
    }

    @Test fun storageWriteFailureDoesNotReplaceCurrentSession() = runBlocking {
        var calls = 0
        withRepository({ json(authResponse(if (++calls == 1) librarian else student)) }) { repository ->
            val current = (repository.login(login) as AuthResult.Success).session
            tokens.failWrite = true
            assertEquals(AuthResult.Failure(AuthError.TEMPORARY_FAILURE), repository.login(login))
            assertEquals(current, repository.session.value)
            assertEquals(TOKEN, tokens.value?.accessToken)
        }
    }

    @Test fun restoreWithoutTokenOrAtExpiryDoesNotCallServer() = runBlocking {
        withRepository({ error("Unexpected network request") }) { repository ->
            assertEquals(RestoreSessionResult.NoSession, repository.restoreSession())
            tokens.value = StoredToken(TOKEN, now)
            assertEquals(RestoreSessionResult.NoSession, repository.restoreSession())
            assertEquals(1, tokens.clears)
            assertNull(tokens.value)
            assertNull(repository.session.value)
        }
    }

    @Test fun restoreUsesStoredTokenAndExpiryWithFreshUser() = runBlocking {
        tokens.value = saved
        withRepository({ request ->
            checkRequest(request, "me", HttpMethod.Get, TOKEN)
            json(userJson(librarian))
        }) { repository ->
            assertEquals(RestoreSessionResult.Restored(AuthSession(TOKEN, expires, librarian)), repository.restoreSession())
            assertEquals(AuthSession(TOKEN, expires, librarian), repository.session.value)
            assertSame(saved, tokens.value)
            assertEquals(0, tokens.writes)
        }
    }

    @Test fun restoreClearsOnlyConfirmedInvalidSessionAndRetriesOtherFailures() = runBlocking {
        tokens.value = saved
        var calls = 0
        withRepository({ request ->
            checkRequest(request, "me", HttpMethod.Get, TOKEN)
            when (++calls) {
                1 -> json(apiError("INVALID_CREDENTIALS"), HttpStatusCode.Unauthorized)
                2 -> throw IOException("connection dropped")
                3 -> json("upstream unavailable", HttpStatusCode.ServiceUnavailable)
                4 -> json(userJson(librarian))
                5 -> json(apiError("INVALID_SESSION"), HttpStatusCode.Unauthorized)
                else -> error("Unexpected extra request")
            }
        }) { repository ->
            repeat(3) {
                assertEquals(RestoreSessionResult.RetryableFailure, repository.restoreSession())
                assertSame(saved, tokens.value)
                assertNull(repository.session.value)
            }
            assertTrue(repository.restoreSession() is RestoreSessionResult.Restored)
            assertEquals(RestoreSessionResult.NoSession, repository.restoreSession())
            assertNull(tokens.value)
            assertNull(repository.session.value)
            assertEquals(1, tokens.clears)
        }
    }

    @Test fun logoutClearsLocalSessionBeforeNetworkCompletes() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        withRepository({ request ->
            if (request.url.encodedPath.endsWith("/login")) {
                json(authResponse(librarian))
            } else {
                checkRequest(request, "logout", HttpMethod.Post, TOKEN)
                started.complete(Unit)
                finish.await()
                respond("", HttpStatusCode.NoContent)
            }
        }) { repository ->
            assertTrue(repository.login(login) is AuthResult.Success)
            val logout = async { repository.logout() }
            withTimeout(2_000) { started.await() }
            assertFalse(logout.isCompleted)
            assertNull(tokens.value)
            assertNull(repository.session.value)
            finish.complete(Unit)
            assertEquals(LogoutResult.SUCCESS, logout.await())
        }
    }

    @Test fun logoutUsesPersistedTokenAndIgnoresNetwork401And5xxFailures() = runBlocking {
        var calls = 0
        withRepository({ request ->
            checkRequest(request, "logout", HttpMethod.Post, TOKEN)
            when (++calls) {
                1 -> throw IOException("connection dropped")
                2 -> json(apiError("INVALID_SESSION"), HttpStatusCode.Unauthorized)
                else -> json("upstream unavailable", HttpStatusCode.BadGateway)
            }
        }) { repository ->
            repeat(3) {
                tokens.value = saved
                assertEquals(LogoutResult.SUCCESS, repository.logout())
                assertNull(tokens.value)
                assertNull(repository.session.value)
            }
            assertEquals(3, calls)
        }
    }

    @Test fun logoutExposesStorageFailureAndCanBeRetried() = runBlocking {
        var calls = 0
        withRepository({ request ->
            if (++calls == 1) json(authResponse(librarian)) else {
                checkRequest(request, "logout", HttpMethod.Post, TOKEN)
                respond("", HttpStatusCode.NoContent)
            }
        }) { repository ->
            val previous = (repository.login(login) as AuthResult.Success).session
            tokens.failClear = true
            assertEquals(LogoutResult.LOCAL_STORAGE_FAILURE, repository.logout())
            assertEquals(previous, repository.session.value)
            assertEquals(TOKEN, tokens.value?.accessToken)
            assertEquals(1, calls)
            tokens.failClear = false
            assertEquals(LogoutResult.SUCCESS, repository.logout())
            assertNull(tokens.value)
            assertNull(repository.session.value)
            assertEquals(2, calls)
        }
    }

    @Test fun logoutClearsSessionWhileLoginOrRegisterIsWaitingForHttp() = runBlocking {
        for (path in listOf("login", "register")) {
            val pending = CompletableDeferred<Unit>()
            val reply = CompletableDeferred<Unit>()
            var loginCalls = 0
            withRepository({ request ->
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "login" -> if (++loginCalls == 1) json(authResponse(librarian)) else {
                        pending.complete(Unit)
                        reply.await()
                        json(authResponse(student))
                    }
                    "register" -> {
                        pending.complete(Unit)
                        reply.await()
                        json(authResponse(student), HttpStatusCode.Created)
                    }
                    "logout" -> {
                        checkRequest(request, "logout", HttpMethod.Post, TOKEN)
                        respond("", HttpStatusCode.NoContent)
                    }
                    else -> error("Unexpected request")
                }
            }) { repository ->
                assertTrue(repository.login(login) is AuthResult.Success)
                val signIn = async {
                    if (path == "login") repository.login(login) else repository.registerStudent(registration)
                }

                withTimeout(2_000) {
                    pending.await()
                    assertEquals(LogoutResult.SUCCESS, repository.logout())
                    assertNull(tokens.value)
                    assertNull(repository.session.value)
                    assertFalse(signIn.isCompleted)
                }
                reply.complete(Unit)
                assertTrue(signIn.await() is AuthResult.Failure)
                assertNull(tokens.value)
                assertNull(repository.session.value)
            }
        }
        assertEquals(2, tokens.writes)
    }

    @Test fun logoutClearsSessionWhileRestoreIsWaitingForHttp() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        withRepository({ request ->
            when (request.url.encodedPath.substringAfterLast('/')) {
                "login" -> json(authResponse(librarian))
                "me" -> {
                    pending.complete(Unit)
                    reply.await()
                    json(userJson(librarian))
                }
                "logout" -> respond("", HttpStatusCode.NoContent)
                else -> error("Unexpected request")
            }
        }) { repository ->
            assertTrue(repository.login(login) is AuthResult.Success)
            val restore = async { repository.restoreSession() }
            withTimeout(2_000) {
                pending.await()
                assertEquals(LogoutResult.SUCCESS, repository.logout())
                assertNull(tokens.value)
                assertNull(repository.session.value)
                assertFalse(restore.isCompleted)
            }
            reply.complete(Unit)
            assertEquals(RestoreSessionResult.NoSession, restore.await())
            assertNull(tokens.value)
            assertNull(repository.session.value)
        }
    }

    @Test fun staleInvalidSessionReplyDoesNotClearNewSignIn() = runBlocking {
        tokens.value = saved
        val pending = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        withRepository({ request ->
            if (request.url.encodedPath.endsWith("/me")) {
                pending.complete(Unit)
                reply.await()
                json(apiError("INVALID_SESSION"), HttpStatusCode.Unauthorized)
            } else {
                json(authResponse(librarian).replace(TOKEN, "A".repeat(43)))
            }
        }) { repository ->
            val restore = async { repository.restoreSession() }
            val newSession = withTimeout(2_000) {
                pending.await()
                (repository.login(login) as AuthResult.Success).session
            }
            reply.complete(Unit)
            assertEquals(RestoreSessionResult.Restored(newSession), restore.await())
            assertSame(newSession, repository.session.value)
            assertTrue(tokens.value?.accessToken == newSession.accessToken)
            assertEquals(0, tokens.clears)
        }
    }

    @Test fun cancellingLogoutWhileItWaitsForALocalWriteStillClearsSession() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        withRepository({ request ->
            if (request.url.encodedPath.endsWith("/login")) json(authResponse(librarian))
            else respond("", HttpStatusCode.NoContent)
        }) { repository ->
            assertTrue(repository.login(login) is AuthResult.Success)
            tokens.beforeWrite = {
                writing.complete(Unit)
                finishWrite.await()
            }
            val signIn = async { repository.login(login) }
            val logout = try {
                withTimeout(2_000) { writing.await() }
                async(start = CoroutineStart.UNDISPATCHED) { repository.logout() }.also {
                    assertFalse(it.isCompleted)
                    it.cancel()
                }
            } finally {
                finishWrite.complete(Unit)
            }
            signIn.await()
            withTimeout(2_000) { logout.join() }
            assertTrue(logout.isCancelled)
            assertNull(tokens.value)
            assertNull(repository.session.value)
        }
    }

    @Test fun expiredAndExactlyExpiredResponsesPreserveTheExistingSession() = runBlocking {
        var loginCalls = 0
        withRepository({ request ->
            if (request.url.encodedPath.endsWith("/register")) {
                json(authResponse(student).replace(expires.toString(), now.toString()), HttpStatusCode.Created)
            } else if (++loginCalls == 1) {
                json(authResponse(librarian))
            } else {
                json(authResponse(student).replace(expires.toString(), (now - 1.seconds).toString()))
            }
        }) { repository ->
            val previous = (repository.login(login) as AuthResult.Success).session
            val previousToken = tokens.value
            val expired = AuthResult.Failure(AuthError.UNKNOWN)
            assertEquals(expired, repository.login(login))
            assertEquals(expired, repository.registerStudent(registration))
            assertSame(previous, repository.session.value)
            assertSame(previousToken, tokens.value)
            assertEquals(1, tokens.writes)
        }
    }

    @Test fun sessionAndSuccessResultsOmitTheBearerTokenFromTheirStringRepresentation() {
        val session = AuthSession(TOKEN, expires, librarian)
        assertFalse(session.toString().contains(TOKEN))
        assertFalse(AuthResult.Success(session).toString().contains(TOKEN))
        assertFalse(RestoreSessionResult.Restored(session).toString().contains(TOKEN))
    }

    @Test fun logoutTimesOutWithLocalSessionAlreadyRemoved() = runBlocking {
        tokens.value = saved
        withRepository({ request ->
            checkRequest(request, "logout", HttpMethod.Post, TOKEN)
            awaitCancellation()
        }) { repository ->
            assertEquals(LogoutResult.SUCCESS, withTimeout(6_000) { repository.logout() })
            assertNull(tokens.value)
            assertNull(repository.session.value)
        }
    }

    @Test fun exceptionsKeepStatusAndCodeWithoutIncludingCredentialsOrResponse() = runBlocking {
        HttpClient(MockEngine {
            json("""{"error":{"code":"INVALID_CREDENTIALS","message":"$TOKEN $PASSWORD","details":{}}}""",
                HttpStatusCode.Unauthorized)
        }) { configureBookioClient() }.use { client ->
            val remote = AuthRemoteDataSource(client, ORIGIN)
            val failure = runCatching { remote.login(login) }.exceptionOrNull() as RemoteAuthFailure
            assertEquals(401, failure.httpStatus)
            assertEquals("INVALID_CREDENTIALS", failure.apiCode)
            assertFalse(failure.stackTraceToString().contains(TOKEN))
            assertFalse(failure.stackTraceToString().contains(PASSWORD))
        }
    }

    private suspend fun withRepository(
        handle: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        test: suspend (DefaultAuthRepository) -> Unit,
    ) {
        HttpClient(MockEngine(handle)) { configureBookioClient() }.use { client ->
            test(DefaultAuthRepository(AuthRemoteDataSource(client, ORIGIN), tokens, object : Clock {
                override fun now() = now
            }))
        }
    }

    private fun checkRequest(request: HttpRequestData, path: String, method: HttpMethod, token: String? = null) {
        assertEquals("$ORIGIN/api/v1/auth/$path", request.url.toString())
        assertEquals(method, request.method)
        if (path == "me" || path == "logout") {
            assertNull(request.headers[HttpHeaders.ContentType])
            assertNull(request.body.contentType)
        }
        assertEquals(token?.let { "Bearer $it" }, request.headers[HttpHeaders.Authorization])
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    private fun apiError(code: String) =
        """{"error":{"code":"$code","message":"Сообщение сервера","details":{"field":"login"}}}"""

    private fun authResponse(user: User) =
        """{"accessToken":"$TOKEN","tokenType":"Bearer","expiresAt":"$expires","user":${userJson(user)}}"""

    private fun userJson(user: User) = """
        {"id":"${user.id}","login":"${user.login}","lastName":"${user.lastName}","firstName":"${user.firstName}",
         "middleName":${user.middleName?.let { "\"$it\"" }},"grade":${user.grade},
         "classLetter":${user.classLetter?.let { "\"$it\"" }},"role":"${user.role}","status":"${user.status}"}
    """.trimIndent()

    private class MemoryTokenStore : TokenStore {
        var value: StoredToken? = null
        var writes = 0
        var clears = 0
        var failWrite = false
        var failClear = false
        var beforeWrite: suspend () -> Unit = {}
        override suspend fun read() = value
        override suspend fun write(token: StoredToken) {
            if (failWrite) throw IOException("write failed")
            beforeWrite()
            writes++
            value = token
        }
        override suspend fun clear() {
            if (failClear) throw IOException("clear failed")
            clears++
            value = null
        }
    }

    private companion object {
        const val ORIGIN = "https://bookio.example"
        const val ID = "00000000-0000-4000-8000-000000000001"
        const val TOKEN = "0123456789012345678901234567890123456789012"
        const val PASSWORD = "  raw-password!  "
    }
}
