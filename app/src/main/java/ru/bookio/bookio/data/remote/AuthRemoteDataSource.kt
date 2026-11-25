package ru.bookio.bookio.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.ContentConvertException
import java.io.IOException
import ru.bookio.bookio.domain.model.AuthSession
import ru.bookio.bookio.domain.model.User
import ru.bookio.bookio.domain.repository.LoginParams
import ru.bookio.bookio.domain.repository.RegisterStudentParams

internal class AuthRemoteDataSource(private val client: HttpClient, origin: String) {
    private val authUrl = "$origin/api/v1/auth"

    suspend fun register(params: RegisterStudentParams): AuthSession =
        request(HttpStatusCode.Created, {
            client.post("$authUrl/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest(
                    params.login, params.password, params.lastName, params.firstName,
                    params.middleName, params.grade, params.classLetter,
                ))
            }
        }) { body<AuthResponse>().toDomain() }

    suspend fun login(params: LoginParams): AuthSession =
        request(HttpStatusCode.OK, {
            client.post("$authUrl/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(params.login, params.password))
            }
        }) { body<AuthResponse>().toDomain() }

    suspend fun me(token: String): User = request(HttpStatusCode.OK, {
        client.get("$authUrl/me") { bearerAuth(token) }
    }) { body<UserResponse>().toDomain() }

    suspend fun logout(token: String): Unit = request(HttpStatusCode.NoContent, {
        client.post("$authUrl/logout") { bearerAuth(token) }
    }) {}

    private suspend fun <T> request(
        expectedStatus: HttpStatusCode,
        send: suspend () -> HttpResponse,
        decode: suspend HttpResponse.() -> T,
    ): T {
        var status: Int? = null
        try {
            val response = send()
            status = response.status.value
            if (response.status != expectedStatus) {
                throw RemoteAuthFailure(status, response.body<ErrorResponse>().error.code)
            }
            return response.decode()
        } catch (_: IOException) {
            throw RemoteAuthFailure(status, networkFailure = true)
        } catch (_: ContentConvertException) {
            throw RemoteAuthFailure(status)
        } catch (_: NoTransformationFoundException) {
            throw RemoteAuthFailure(status)
        } catch (_: IllegalArgumentException) {
            throw RemoteAuthFailure(status)
        }
    }
}

internal class RemoteAuthFailure(
    val httpStatus: Int?,
    val apiCode: String? = null,
    val networkFailure: Boolean = false,
) : Exception() {
    val isTemporary: Boolean
        get() = networkFailure || httpStatus in 500..599 || (httpStatus in 400..499 && apiCode == "RATE_LIMITED")
}
