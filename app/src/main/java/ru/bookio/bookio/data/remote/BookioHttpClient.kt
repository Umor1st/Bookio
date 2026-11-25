package ru.bookio.bookio.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.RequestBody
import okio.BufferedSink

internal fun createBookioHttpClient(): HttpClient = HttpClient(OkHttp) {
    configureBookioClient()
    engine {
        config {
            retryOnConnectionFailure(false)
            followRedirects(false)
            followSslRedirects(false)
            addInterceptor { chain ->
                val request = chain.request()
                val body = request.body
                if (request.method != "POST" || body == null) return@addInterceptor chain.proceed(request)

                // OkHttp can replay a POST after HTTP 503 with Retry-After: 0.
                val oneShotBody = object : RequestBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun isOneShot() = true
                    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
                }
                chain.proceed(request.newBuilder().post(oneShotBody).build())
            }
        }
    }
}

internal fun HttpClientConfig<*>.configureBookioClient() {
    expectSuccess = false
    followRedirects = false
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 15_000
    }
}
