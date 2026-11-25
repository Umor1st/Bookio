package ru.bookio.bookio.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import ru.bookio.bookio.BuildConfig
import ru.bookio.bookio.data.local.AndroidSessionKey
import ru.bookio.bookio.data.local.EncryptedTokenStore
import ru.bookio.bookio.data.local.StoredToken

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreTokenStoreTest {
    @Test
    fun encryptsAndReadsSessionWithAndroidKeystore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.noBackupFilesDir, "bookio-keystore-test-${UUID.randomUUID()}")
        val token = StoredToken("keystore-test-${UUID.randomUUID()}", Clock.System.now() + 1.days)
        val origin = BuildConfig.API_ORIGIN
        val store = EncryptedTokenStore(file, AndroidSessionKey(), origin)
        try {
            store.write(token)
            assertTrue("The encrypted session file must exist", file.isFile)
            val ciphertext = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse("The file contains the plaintext access token", ciphertext.contains(token.accessToken))
            assertFalse("The file contains the plaintext expiry", ciphertext.contains(token.expiresAt.toString()))

            val reopenedStore = EncryptedTokenStore(file, AndroidSessionKey(), origin)
            val restored = reopenedStore.read()
            assertTrue("The reloaded access token must match", restored?.accessToken == token.accessToken)
            assertEquals(token.expiresAt, restored?.expiresAt)
        } finally {
            store.clear()
        }
        assertFalse("The test session file must be removed", file.exists())
    }
}
