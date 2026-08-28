package space.bitos.app.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android secure secret storage (ID-003 skeleton).
 *
 * The identity secret never lands in plaintext storage: it is sealed with an
 * AES-256-GCM key that lives in AndroidKeyStore (hardware-backed where
 * available) and never leaves it. Only the sealed blob is persisted. Wiping
 * the Keystore entry or the prefs row destroys access; nothing secret is
 * ever logged or serialized elsewhere.
 */
class SecureKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences("bitos_identity", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun hasSecret(): Boolean = prefs.contains(BLOB_KEY)

    /** @param secretHex hex64 secret to seal. */
    fun storeSecret(secretHex: String) {
        seal(BLOB_KEY, secretHex)
    }

    /** @return hex64 secret, or null when absent/unsealable. */
    fun loadSecret(): String? = unseal(BLOB_KEY)

    // ── Multi-account slots (APP-018a row 1) ───────────────────────

    /** Seals a secret under its pubkey-keyed slot (`identity_blob_<pk>`). */
    fun storeSecret(secretHex: String, slotPubkey: String) {
        seal(blobKey(slotPubkey), secretHex)
    }

    /** Slot secret by pubkey, or null when absent/unsealable. */
    fun loadSecret(slotPubkey: String): String? = unseal(blobKey(slotPubkey))

    /** Pubkeys that currently have a sealed slot (legacy active slot excluded). */
    fun slotPubkeys(): List<String> =
        prefs.all.keys
            .filter { it.startsWith(SLOT_PREFIX) }
            .map { it.removePrefix(SLOT_PREFIX) }
            .filter { it.length == 64 }

    /** Destructively wipes one account slot (registry row removed by caller). */
    fun removeSecret(slotPubkey: String) {
        prefs.edit().remove(blobKey(slotPubkey)).apply()
    }

    private fun blobKey(slotPubkey: String): String = SLOT_PREFIX + slotPubkey

    private fun seal(key: String, secretHex: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(secretHex.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    private fun unseal(key: String): String? = runCatching {
        val blob = Base64.decode(prefs.getString(key, null) ?: return null, Base64.NO_WRAP)
        if (blob.size <= IV_LENGTH) return null
        val iv = blob.copyOfRange(0, IV_LENGTH)
        val ciphertext = blob.copyOfRange(IV_LENGTH, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    fun clear() {
        prefs.edit().remove(BLOB_KEY).apply()
    }

    /** Generates a fresh CSPRNG secret (hex64) for account creation. */
    fun generateSecretHex(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { ((it.toInt() and 0xf0) ushr 4).toString(16) + (it.toInt() and 0x0f).toString(16) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bitos_identity_aes"
        const val BLOB_KEY = "identity_blob"
        const val SLOT_PREFIX = "identity_blob_"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
