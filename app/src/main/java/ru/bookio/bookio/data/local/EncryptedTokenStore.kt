package ru.bookio.bookio.data.local

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

internal interface SessionKey {
    fun load(): SecretKey?
    fun getOrCreate(): SecretKey
    fun delete()
}

internal class EncryptedTokenStore(
    private val file: File,
    private val key: SessionKey,
    origin: String,
) : TokenStore {
    private val associatedData = origin.encodeToByteArray()

    override suspend fun read(): StoredToken? = onDisk {
        val path = file.toPath()
        val encrypted = try {
            if (Files.size(path) > MAX_PAYLOAD_BYTES) {
                Files.deleteIfExists(path)
                return@onDisk null
            }
            Files.readAllBytes(path)
        } catch (_: NoSuchFileException) {
            return@onDisk null
        }
        decode(encrypted) ?: run {
            Files.deleteIfExists(file.toPath())
            null
        }
    }

    override suspend fun write(token: StoredToken) = onDisk {
        val cipher = try {
            encryptionCipher()
        } catch (_: KeyPermanentlyInvalidatedException) {
            key.delete()
            encryptionCipher()
        }
        cipher.updateAAD(associatedData)
        val payload = Json.encodeToString(Payload(token.accessToken, token.expiresAt.toString()))
        val encrypted = cipher.iv + cipher.doFinal(payload.encodeToByteArray())
        val temporary = Files.createTempFile(file.parentFile!!.toPath(), "bookio-auth-", ".tmp")
        try {
            temporary.toFile().outputStream().use { output ->
                output.write(encrypted)
                output.fd.sync()
            }
            Files.move(temporary, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        Unit
    }

    override suspend fun clear() = onDisk {
        Files.deleteIfExists(file.toPath())
        Unit
    }

    private fun decode(encrypted: ByteArray): StoredToken? {
        if (encrypted.size <= IV_BYTES + TAG_BYTES) return null
        return try {
            val secret = key.load() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BYTES * 8, encrypted, 0, IV_BYTES))
            cipher.updateAAD(associatedData)
            val plaintext = cipher.doFinal(encrypted, IV_BYTES, encrypted.size - IV_BYTES)
            val payload = Json.decodeFromString<Payload>(plaintext.decodeToString(throwOnInvalidSequence = true))
            require(payload.accessToken.isNotBlank())
            StoredToken(payload.accessToken, Instant.parse(payload.expiresAt))
        } catch (_: AEADBadTagException) {
            null
        } catch (_: KeyPermanentlyInvalidatedException) {
            key.delete()
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private fun encryptionCipher(): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, key.getOrCreate())
    }

    private suspend fun <T> onDisk(operation: () -> T): T = withContext(Dispatchers.IO) {
        try {
            operation()
        } catch (_: GeneralSecurityException) {
            throw IOException("Could not access the encrypted session")
        } catch (_: SecurityException) {
            throw IOException("Could not access the encrypted session")
        } catch (_: java.security.ProviderException) {
            throw IOException("Could not access the encrypted session")
        }
    }

    @Serializable
    private class Payload(val accessToken: String, val expiresAt: String)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val MAX_PAYLOAD_BYTES = 4_096
    }
}
