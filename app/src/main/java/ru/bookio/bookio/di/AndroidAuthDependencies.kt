package ru.bookio.bookio.di

import android.content.Context
import java.io.File
import ru.bookio.bookio.BuildConfig
import ru.bookio.bookio.data.local.AndroidSessionKey
import ru.bookio.bookio.data.local.EncryptedTokenStore
import ru.bookio.bookio.data.remote.AuthRemoteDataSource
import ru.bookio.bookio.data.remote.createBookioHttpClient
import ru.bookio.bookio.data.repository.DefaultAuthRepository

object AndroidAuthDependencies {
    private var instance: AuthDependencies? = null

    @Synchronized
    fun get(context: Context): AuthDependencies = instance ?: run {
        val origin = BuildConfig.API_ORIGIN
        val tokens = EncryptedTokenStore(
            File(context.applicationContext.noBackupFilesDir, "bookio-auth-session"),
            AndroidSessionKey(),
            origin,
        )
        val remote = AuthRemoteDataSource(createBookioHttpClient(), origin)
        AuthDependencies(DefaultAuthRepository(remote, tokens)).also { instance = it }
    }
}
