package ru.bookio.bookio.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal class AndroidSessionKey : SessionKey {
    override fun load(): SecretKey? = openKeyStore().getKey(ALIAS, null) as? SecretKey

    override fun delete() {
        openKeyStore().deleteEntry(ALIAS)
    }

    private fun openKeyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun getOrCreate(): SecretKey =
        load() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "bookio-auth-session"
    }
}
