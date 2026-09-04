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
import space.bitos.core.identity.KeyImportForm
import space.bitos.core.identity.KeyImportVerdict
import space.bitos.core.identity.NostrKeyCodec
import space.bitos.core.identity.SignerKind
import space.bitos.core.nostr.Sha256EventHasher

/** A pending identity awaiting explicit user confirmation (ID-004). */
data class IdentityPreview(
    /** Derived once at preview creation so confirmation needs no second secp256k1 pass. */
    val pubkeyHex: String,
    val npub: String,
    val secretHex: String,
    val replacesExisting: Boolean,
    /** True when the key was generated here — the confirm gate offers the one-time backup reveal only then. */
    val isNewKey: Boolean,
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

    /** Multi-account registry (APP-018a row 1) — public projections only. */
    private val registry = AccountRegistryStore(application)
    val registeredAccounts = registry.accounts
    val activeRegistryPubkey = registry.activePubkey

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
            val secret = withContext(Dispatchers.IO) {
                // Active slot first (multi-account); legacy single-secret fallback.
                registry.activePubkey.value?.let { store.loadSecret(slotPubkey = it) }
                    ?: store.loadSecret()
            } ?: return@launch
            val identity = identityFor(secret) ?: return@launch
            // Legacy migration: an account created before the registry ships
            // backfills its row so the switcher shows it (and Add works).
            if (registry.accounts.value.none { it.pubkeyHex == identity.pubkeyHex }) {
                withContext(Dispatchers.IO) { store.storeSecret(secret, slotPubkey = identity.pubkeyHex) }
                registry.register(
                    space.bitos.core.identity.RegisteredAccount(
                        pubkeyHex = identity.pubkeyHex,
                        npub = identity.npub,
                        addedAtSeconds = System.currentTimeMillis() / 1_000,
                    ),
                    makeActive = registry.activePubkey.value == null,
                )
            }
            mutableState.value = mutableState.value.copy(account = identity)
        }
    }

    /** Prepares a freshly generated key for confirmation (derivation off-main). */
    fun createKeyPreview() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true)
            val secret = store.generateSecretHex()
            val identity = identityFor(secret)
            if (identity == null) {
                mutableState.value = mutableState.value.copy(busy = false)
                return@launch
            }
            mutableState.value = mutableState.value.copy(
                preview = IdentityPreview(
                    pubkeyHex = identity.pubkeyHex,
                    npub = identity.npub,
                    secretHex = secret,
                    replacesExisting = mutableState.value.account != null,
                    isNewKey = true,
                ),
                importError = null,
                busy = false,
            )
        }
    }

    /** Validates an nsec/hex import and prepares it for confirmation (derivation off-main). */
    fun importNsecPreview(input: String) {
        val check = KeyImportForm.check(input)
        val secret = when (check.verdict) {
            KeyImportVerdict.READY -> check.secretHex ?: return
            // Live field feedback already shows the same copy; keep the
            // submit error in state for the supporting-text slot.
            else -> {
                mutableState.value = mutableState.value.copy(importError = check.message)
                return
            }
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true)
            val identity = identityFor(secret)
            if (identity == null) {
                mutableState.value = mutableState.value.copy(importError = "Key rejected by the signer.", busy = false)
                return@launch
            }
            mutableState.value = mutableState.value.copy(
                preview = IdentityPreview(
                    pubkeyHex = identity.pubkeyHex,
                    npub = identity.npub,
                    secretHex = secret,
                    replacesExisting = mutableState.value.account != null,
                    isNewKey = false,
                ),
                importError = null,
                busy = false,
            )
        }
    }

    /** Clears a stale submit error while the user edits the field. */
    fun clearImportError() {
        if (mutableState.value.importError != null) {
            mutableState.value = mutableState.value.copy(importError = null)
        }
    }

    /**
     * nsec encoding of the pending preview secret. Only the confirm-gate
     * backup reveal may display it; never logged or persisted.
     */
    fun previewNsec(): String? =
        mutableState.value.preview?.let { NostrKeyCodec.nsec(it.secretHex) }

    /** Stores the previewed identity; the visible npub is the confirmation.
     *  The preview already carries the derived pubkey — no second pass. */
    fun confirmPreview() {
        val preview = mutableState.value.preview ?: return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true)
            val identity = identityFor(preview.secretHex) ?: run {
                mutableState.value = mutableState.value.copy(busy = false)
                return@launch
            }
            withContext(Dispatchers.IO) {
                store.storeSecret(preview.secretHex)
                store.storeSecret(preview.secretHex, slotPubkey = identity.pubkeyHex)
            }
            registry.register(
                space.bitos.core.identity.RegisteredAccount(
                    pubkeyHex = identity.pubkeyHex,
                    npub = identity.npub,
                    addedAtSeconds = System.currentTimeMillis() / 1_000,
                ),
                makeActive = true,
            )
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

    /**
     * Sign-out = deactivate (legacy parity): the ACTIVE pointer clears but
     * every sealed slot and registry row survives — one-tap switch back in.
     */
    fun signOut() {
        registry.setActive(null)
        mutableState.value = IdentityUiState()
    }

    /**
     * Destructive: removes the sealed secret after explicit confirmation.
     * Hops to the next sealed account instead of dropping to browse (same
     * rationale as [removeRegisteredAccount]).
     */
    fun removeAccount() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clear() }
            val removed = mutableState.value.account?.pubkeyHex
            registry.setActive(null)
            val next = registeredAccounts.value.firstOrNull { it.pubkeyHex != removed }
            if (next != null) switchTo(next.pubkeyHex) else mutableState.value = IdentityUiState()
        }
    }

    /** One-tap account switch (registry row → sealed slot → active). */
    fun switchTo(pubkeyHex: String) {
        if (mutableState.value.account?.pubkeyHex == pubkeyHex) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true)
            val secret = withContext(Dispatchers.IO) { store.loadSecret(slotPubkey = pubkeyHex) }
            val identity = secret?.let { identityFor(it) }
            if (identity == null) {
                // Slot lost (keychain wipe): drop the dead row.
                registry.remove(pubkeyHex)
                mutableState.value = mutableState.value.copy(busy = false)
                return@launch
            }
            registry.setActive(pubkeyHex)
            mutableState.value = mutableState.value.copy(
                account = identity,
                preview = null,
                busy = false,
            )
        }
    }

    /**
     * Destructive per-account removal: wipes the sealed slot + registry row.
     * Removing the ACTIVE account hops to the next sealed account instead of
     * dropping to browse — the signed-out shell has no switcher, so without
     * the hop the remaining accounts would be unreachable.
     */
    fun removeRegisteredAccount(pubkeyHex: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.removeSecret(pubkeyHex) }
            registry.remove(pubkeyHex)
            if (mutableState.value.account?.pubkeyHex == pubkeyHex) {
                val next = registeredAccounts.value.firstOrNull { it.pubkeyHex != pubkeyHex }
                if (next != null) switchTo(next.pubkeyHex) else mutableState.value = IdentityUiState()
            }
        }
    }

    /** Publishes a kind-0 profile through the receipt machine. */
    fun publishProfile(name: String, displayName: String, about: String, nip05: String, lud16: String, picture: String = "", banner: String = "", website: String = "") {
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
                picture = picture,
                nip05 = nip05,
                lud16 = lud16,
                banner = banner,
                website = website,
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

    /** Transient signer from the active account's sealed slot; null when none. */
    suspend fun createSigner(): LocalKeySigner? = withContext(Dispatchers.IO) {
        (registry.activePubkey.value?.let { store.loadSecret(slotPubkey = it) } ?: store.loadSecret())
            ?.let(::LocalKeySigner)
    }

    /**
     * Security-section reveal (APP-018): loads the sealed secret and returns
     * its nsec encoding. Confirm-gated by the caller; never logged or cached
     * here — the value crosses to the view exactly once per reveal.
     */
    suspend fun revealNsec(): String? = withContext(Dispatchers.IO) {
        // Active slot first (multi-account); the legacy slot only matches the
        // original install key — same resolution as [createSigner].
        (registry.activePubkey.value?.let { store.loadSecret(slotPubkey = it) } ?: store.loadSecret())
            ?.let { NostrKeyCodec.nsec(it) }
    }

    /** secp256k1 scalar multiplication is ~50ms — always off the main thread. */
    private suspend fun identityFor(secretHex: String): AccountIdentity? = withContext(Dispatchers.Default) {
        val publicKey = SchnorrSigning.publicKey(hexBytes(secretHex), Sha256EventHasher) ?: return@withContext null
        val pubkeyHex = publicKey.joinToString("") { ((it.toInt() and 0xf0) ushr 4).toString(16) + (it.toInt() and 0x0f).toString(16) }
        val npub = NostrKeyCodec.npub(pubkeyHex) ?: return@withContext null
        AccountIdentity(
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
