package space.bitos.app.identity

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.bitos.core.crypto.SchnorrSigning
import space.bitos.core.identity.AccountIdentity
import space.bitos.core.identity.NostrKeyCodec
import space.bitos.core.identity.SignerKind
import space.bitos.core.nostr.Sha256EventHasher

/** A pending identity awaiting explicit user confirmation (ID-004). */
data class IdentityPreview(
    val npub: String,
    val secretHex: String,
    val replacesExisting: Boolean,
)

data class IdentityUiState(
    val account: AccountIdentity? = null,
    val preview: IdentityPreview? = null,
    val importError: String? = null,
    val busy: Boolean = false,
)

/**
 * Account lifecycle controller. Secrets exist in memory only inside the
 * import/create transaction (the preview) until confirmed; after
 * confirmation they live sealed in [SecureKeyStore] and the UI state holds
 * only the non-secret [AccountIdentity] projection.
 */
class IdentityViewModel(
    application: Application,
    private val notePublisher: space.bitos.app.data.publish.NotePublisher? = null,
) : AndroidViewModel(application) {

    private val store = SecureKeyStore(application)

    private val mutableState = MutableStateFlow(IdentityUiState())
    val state: StateFlow<IdentityUiState> = mutableState.asStateFlow()

    data class ProfileEditState(
        val busy: Boolean = false,
        val error: String? = null,
        val published: Boolean = false,
    )

    private val mutableProfileEdit = MutableStateFlow(ProfileEditState())
    val profileEditState: StateFlow<ProfileEditState> = mutableProfileEdit.asStateFlow()

    init {
        loadExisting()
    }

    private fun loadExisting() {
        viewModelScope.launch {
            val secret = withContext(Dispatchers.IO) { store.loadSecret() } ?: return@launch
            val identity = identityFor(secret) ?: return@launch
            mutableState.value = mutableState.value.copy(account = identity)
        }
    }

    /** Prepares a freshly generated key for confirmation. */
    fun createKeyPreview() {
        val secret = store.generateSecretHex()
        val identity = identityFor(secret) ?: return
        mutableState.value = mutableState.value.copy(
            preview = IdentityPreview(
                npub = identity.npub,
                secretHex = secret,
                replacesExisting = mutableState.value.account != null,
            ),
            importError = null,
        )
    }

    /** Validates an nsec import and prepares it for confirmation. */
    fun importNsecPreview(input: String) {
        val trimmed = input.trim()
        val secret = NostrKeyCodec.parseNsec(trimmed)
            ?: NostrKeyCodec.parseNpub(trimmed)?.let {
                mutableState.value = mutableState.value.copy(importError = "That is a public key (npub); import needs the secret (nsec).")
                null
            }
            ?: run {
                if (mutableState.value.importError == null) {
                    mutableState.value = mutableState.value.copy(importError = "Not a valid nsec key.")
                }
                null
            }
        val resolved = secret ?: return
        val identity = identityFor(resolved) ?: run {
            mutableState.value = mutableState.value.copy(importError = "Key rejected by the signer.")
            return
        }
        mutableState.value = mutableState.value.copy(
            preview = IdentityPreview(
                npub = identity.npub,
                secretHex = resolved,
                replacesExisting = mutableState.value.account != null,
            ),
            importError = null,
        )
    }

    /** Stores the previewed identity; the visible npub is the confirmation. */
    fun confirmPreview() {
        val preview = mutableState.value.preview ?: return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true)
            withContext(Dispatchers.IO) { store.storeSecret(preview.secretHex) }
            val identity = identityFor(preview.secretHex) ?: return@launch
            mutableState.value = mutableState.value.copy(
                account = identity,
                preview = null,
                busy = false,
            )
        }
    }

    fun cancelPreview() {
        mutableState.value = mutableState.value.copy(preview = null, importError = null)
    }

    /** Destructive: removes the sealed secret after explicit confirmation. */
    fun removeAccount() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clear() }
            mutableState.value = IdentityUiState()
        }
    }

    /** Publishes a kind-0 profile through the receipt machine. */
    fun publishProfile(name: String, displayName: String, about: String, nip05: String, lud16: String) {
        val publisher = this.notePublisher ?: run {
            mutableProfileEdit.value = ProfileEditState(error = "Publisher unavailable.")
            return
        }
        mutableProfileEdit.value = ProfileEditState(busy = true)
        viewModelScope.launch {
            publisher.publishProfile(
                name = name,
                displayName = displayName,
                about = about,
                picture = "",
                nip05 = nip05,
                lud16 = lud16,
                signerProvider = { createSigner() },
                writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
            )
            // Settle after the publish machine's window.
            delay(1_000)
            mutableProfileEdit.value = ProfileEditState(published = true)
        }
    }

    fun clearProfileEditError() {
        mutableProfileEdit.value = ProfileEditState()
    }

    /** Transient signer from the sealed secret; null when no account is stored. */
    suspend fun createSigner(): LocalKeySigner? = withContext(Dispatchers.IO) {
        store.loadSecret()?.let(::LocalKeySigner)
    }

    /**
     * Security-section reveal (APP-018): loads the sealed secret and returns
     * its nsec encoding. Confirm-gated by the caller; never logged or cached
     * here — the value crosses to the view exactly once per reveal.
     */
    suspend fun revealNsec(): String? = withContext(Dispatchers.IO) {
        store.loadSecret()?.let { NostrKeyCodec.nsec(it) }
    }

    private fun identityFor(secretHex: String): AccountIdentity? {
        val publicKey = SchnorrSigning.publicKey(hexBytes(secretHex), Sha256EventHasher) ?: return null
        val pubkeyHex = publicKey.joinToString("") { ((it.toInt() and 0xf0) ushr 4).toString(16) + (it.toInt() and 0x0f).toString(16) }
        val npub = NostrKeyCodec.npub(pubkeyHex) ?: return null
        return AccountIdentity(
            pubkeyHex = pubkeyHex,
            npub = npub,
            signerKind = SignerKind.LOCAL_KEY,
            createdAt = System.currentTimeMillis(),
        )
    }

    private fun hexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    companion object {
        fun factory(
            app: Application,
            notePublisher: space.bitos.app.data.publish.NotePublisher? = null,
        ): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return IdentityViewModel(app, notePublisher) as T
                }
            }
    }
}
