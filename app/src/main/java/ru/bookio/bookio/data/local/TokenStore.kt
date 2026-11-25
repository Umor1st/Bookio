package ru.bookio.bookio.data.local

import kotlin.time.Instant

internal class StoredToken(val accessToken: String, val expiresAt: Instant)

internal interface TokenStore {
    suspend fun read(): StoredToken?
    suspend fun write(token: StoredToken)
    suspend fun clear()
}
