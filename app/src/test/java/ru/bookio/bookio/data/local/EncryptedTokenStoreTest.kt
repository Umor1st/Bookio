package ru.bookio.bookio.data.local

import java.io.File
import java.io.IOException
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Instant

class EncryptedTokenStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private val key = TestKey()
    private val token = StoredToken("private-token-for-storage-test", Instant.parse("2026-12-25T12:00:00Z"))

    @Test fun encryptedPayloadRoundTripsAcrossInstancesAndUsesFreshIv() = runBlocking {
        val file = File(directory.root, "session")
        val store = EncryptedTokenStore(file, key, ORIGIN)
        assertNull(store.read())
        store.write(token)
        val first = file.readBytes()
        assertFalse(first.decodeToString().contains(token.accessToken))
        assertFalse(first.decodeToString().contains(token.expiresAt.toString()))
        store.write(token)
        assertFalse(first.copyOfRange(0, 12).contentEquals(file.readBytes().copyOfRange(0, 12)))

        val restarted = EncryptedTokenStore(file, key, ORIGIN)
        val restored = restarted.read()
        assertEquals(token.accessToken, restored?.accessToken)
        assertEquals(token.expiresAt, restored?.expiresAt)
        restarted.clear()
        assertFalse(file.exists())
        assertNull(store.read())
        store.clear()
    }

    @Test fun corruptedOrTruncatedDataIsRemoved() = runBlocking {
        val file = File(directory.root, "session")
        val store = EncryptedTokenStore(file, key, ORIGIN)
        store.write(token)
        val damaged = file.readBytes()
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
        for (payload in listOf(damaged, byteArrayOf(1, 2, 3), ByteArray(4_097))) {
            file.writeBytes(payload)
            assertNull(store.read())
            assertFalse(file.exists())
        }
    }

    @Test fun missingKeyOrChangedOriginDiscardsStoredSession() = runBlocking {
        val file = File(directory.root, "session")
        val store = EncryptedTokenStore(file, key, ORIGIN)
        store.write(token)
        assertNull(EncryptedTokenStore(file, TestKey(), ORIGIN).read())
        assertFalse(file.exists())

        store.write(token)
        assertNull(EncryptedTokenStore(file, key, "https://another.example").read())
        assertFalse(file.exists())
    }

    @Test fun ambiguousKeyAndProviderErrorsPreserveTheFileAndAllowRetry() = runBlocking {
        val file = File(directory.root, "session")
        val store = EncryptedTokenStore(file, key, ORIGIN)
        store.write(token)
        val encrypted = file.readBytes()

        key.overrideLoad = { SecretKeySpec(byteArrayOf(1), "AES") }
        assertTrue(runCatching { store.read() }.exceptionOrNull() is IOException)
        assertArrayEquals(encrypted, file.readBytes())

        key.overrideLoad = { throw ProviderException("Keystore temporarily unavailable") }
        assertTrue(runCatching { store.read() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { store.write(token) }.exceptionOrNull() is IOException)
        assertArrayEquals(encrypted, file.readBytes())

        key.overrideLoad = null
        assertEquals(token.accessToken, store.read()?.accessToken)
        assertEquals(0, key.deletions)
    }

    private class TestKey : SessionKey {
        private var key: SecretKey? = null
        var overrideLoad: (() -> SecretKey)? = null
        var deletions = 0
        override fun load() = overrideLoad?.invoke() ?: key
        override fun delete() {
            deletions++
            key = null
        }

        override fun getOrCreate(): SecretKey = load() ?: KeyGenerator.getInstance("AES").run {
            init(256)
            generateKey()
        }.also { key = it }
    }

    private companion object {
        const val ORIGIN = "https://bookio.example"
    }
}
