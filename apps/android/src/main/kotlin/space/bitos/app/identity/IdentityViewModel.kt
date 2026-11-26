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
    /** True once the cold-start restore attempt finished — account found OR
     *  absent. Guest chrome (the "Get started" banner) must wait for this:
     *  gating on [account] alone flashed the banner to every signed-in user
     *  for the whole Keystore-decrypt + pubkey-derive window. */
    val identityResolved: Boolean = false,
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
            try {
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
                mutableState.value = mutableState.value.copy(account = identity, identityResolved = true)
            } catch (failure: Exception) {
                // A dead key slot degrades to the guest state — identity must
                // always resolve, or the banner would never appear or hide.
            } finally {
                mutableState.value = mutableState.value.copy(identityResolved = true)
            }
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
     * In-flight remote-signer waits are cancelled and their lanes torn
     * down (ID-006d); no secrets are wiped by sign-out.
     */
    fun signOut() {
        shutdownRemoteSigners(wipeTransportSecrets = false)
        registry.setActive(null)
        mutableState.value = IdentityUiState()
    }

    /**
     * ID-006d: switch, sign-out, removal, timeout and app termination all
     * land here — cancel in-flight requests, close subscriptions, tear
     * down sockets and (on removal) wipe the sealed transport keys.
     */
    private fun shutdownRemoteSigners(wipeTransportSecrets: Boolean, wipeForPubkey: String? = null) {
        viewModelScope.launch {
            activeNip46Session.value?.let { session ->
                session.shutdown()
                if (wipeTransportSecrets) session.wipeTransportSecret()
            }
            if (wipeTransportSecrets) {
                wipeForPubkey?.let {
                    space.bitos.app.identity.nip46.SecureNip46TransportSecretStore(store).removeTransportSecret(it)
                }
            }
            _activeNip46Session.value = null
            activeNip55Client.value?.cancelWait()
            _activeNip55Client.value = null
        }
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
        // ID-006d: the previous account's signer lane dies with the switch.
        shutdownRemoteSigners(wipeTransportSecrets = false)
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
            // ID-006d: removal wipes the account's native signer state —
            // in-flight waits cancel, the NIP-46 transport key slot dies.
            shutdownRemoteSigners(wipeTransportSecrets = true, wipeForPubkey = pubkeyHex)
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

    // ── Remote signer setup (ID-006b / ID-007a) ────────────────────

    /** One in-flight remote-signer setup awaiting user confirmation. */
    data class RemoteSetupPreview(
        val kind: SignerKind,
        /** The account pubkey the signer itself returned (never assumed). */
        val pubkeyHex: String,
        val npub: String,
        /** NIP-46 only: the connection the account will persist. */
        val nip46: space.bitos.core.identity.Nip46AccountMetadata? = null,
        /** NIP-55 only: the pinned signer package. */
        val nip55Package: String? = null,
    )

    private val mutableRemoteSetup = MutableStateFlow<RemoteSetupPreview?>(null)
    val remoteSetup: StateFlow<RemoteSetupPreview?> = mutableRemoteSetup.asStateFlow()

    private val mutableRemoteSetupError = MutableStateFlow<String?>(null)
    val remoteSetupError: StateFlow<String?> = mutableRemoteSetupError.asStateFlow()

    private val mutableRemoteSetupBusy = MutableStateFlow(false)
    val remoteSetupBusy: StateFlow<Boolean> = mutableRemoteSetupBusy.asStateFlow()

    /**
     * bunker:// setup (ID-006b): parse, connect, and require the bunker's
     * own `get_public_key` answer before anything is saved. No account
     * exists until the user confirms the returned npub. The transport key
     * slot is keyed by the RETURNED pubkey, so an unconfirmed attempt
     * leaves nothing persisted behind the registry write.
     */
    fun startNip46Setup(uri: String) {
        if (!IdentityFeatureFlags.nip46Enabled) return
        val connection = space.bitos.core.identity.nip46.Nip46Uri.parseBunker(uri) ?: run {
            mutableRemoteSetupError.value = "That bunker link doesn't look valid."
            return
        }
        viewModelScope.launch {
            mutableRemoteSetupBusy.value = true
            mutableRemoteSetupError.value = null
            val session = space.bitos.app.identity.nip46.Nip46SignerSession(
                scope = (getApplication() as? space.bitos.app.BitOsApplication)?.applicationScope ?: viewModelScope,
                metadata = space.bitos.core.identity.Nip46AccountMetadata(
                    bunkerPubkeyHex = connection.remotePubkeyHex,
                    relays = connection.relays.map { it.value },
                ),
                accountPubkeyHex = "", // learned from the handshake below
                secretStore = object : space.bitos.app.identity.nip46.Nip46TransportSecretStore {
                    // Setup holds the transport key in memory only; the
                    // persisted slot is created by the confirmed session.
                    override fun loadOrCreateTransportSecret(accountPubkeyHex: String): String? = store.generateSecretHex()
                    override fun removeTransportSecret(accountPubkeyHex: String) {}
                },
                transportFactory = { relay, _ ->
                    space.bitos.app.data.relay.OkHttpRelayTransport(
                        relay, space.bitos.app.data.relay.relayHttpClient(),
                    ) { }
                },
                setupTimeoutMs = 30_000,
            )
            val result = session.connectAndGetPublicKey(connection.secret)
            mutableRemoteSetupBusy.value = false
            when (result) {
                is space.bitos.app.identity.nip46.Nip46SetupResult.Connected -> {
                    val pubkey = result.accountPubkeyHex
                    val npub = NostrKeyCodec.npub(pubkey) ?: run {
                        mutableRemoteSetupError.value = "The signer returned an unusable key."
                        return@launch
                    }
                    mutableRemoteSetup.value = RemoteSetupPreview(
                        kind = SignerKind.REMOTE_NIP46,
                        pubkeyHex = pubkey,
                        npub = npub,
                        nip46 = space.bitos.core.identity.Nip46AccountMetadata(
                            bunkerPubkeyHex = connection.remotePubkeyHex,
                            relays = connection.relays.map { it.value },
                        ),
                    )
                }
                is space.bitos.app.identity.nip46.Nip46SetupResult.Failed ->
                    mutableRemoteSetupError.value = remoteFailureText(result.result)
            }
        }
    }

    /** NIP-55 setup (ID-007a): a validated get_public_key result awaits confirmation. */
    fun prepareNip55Setup(pubkeyHex: String, signerPackage: String) {
        if (!IdentityFeatureFlags.nip55Enabled) return
        val npub = NostrKeyCodec.npub(pubkeyHex) ?: run {
            mutableRemoteSetupError.value = "The signer returned an unusable key."
            return
        }
        mutableRemoteSetup.value = RemoteSetupPreview(
            kind = SignerKind.EXTERNAL_NIP55,
            pubkeyHex = pubkeyHex,
            npub = npub,
            nip55Package = signerPackage,
        )
    }

    /** Saves the confirmed remote account (public metadata only). */
    fun confirmRemoteSetup() {
        val preview = mutableRemoteSetup.value ?: return
        registry.register(
            space.bitos.core.identity.RegisteredAccount(
                pubkeyHex = preview.pubkeyHex,
                npub = preview.npub,
                addedAtSeconds = System.currentTimeMillis() / 1_000,
                signerKind = preview.kind,
                nip46 = preview.nip46,
                nip55 = preview.nip55Package?.let {
                    space.bitos.core.identity.Nip55AccountMetadata(signerPackage = it)
                },
            ),
            makeActive = true,
        )
        mutableRemoteSetup.value = null
        mutableRemoteSetupError.value = null
        mutableState.value = mutableState.value.copy(
            account = AccountIdentity(
                pubkeyHex = preview.pubkeyHex,
                npub = preview.npub,
                signerKind = preview.kind,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    fun cancelRemoteSetup() {
        mutableRemoteSetup.value = null
        mutableRemoteSetupError.value = null
    }

    private fun remoteFailureText(result: space.bitos.core.identity.SignEventResult): String = when (result) {
        is space.bitos.core.identity.SignEventResult.TimedOut -> "The signer didn't answer in time. Try again when it's open."
        is space.bitos.core.identity.SignEventResult.Unavailable -> "Couldn't reach the signer's relays. Check the link and your connection."
        is space.bitos.core.identity.SignEventResult.Denied -> "The signer refused the connection."
        else -> "The signer couldn't complete the connection."
    }

    /** Transient signer for the active account; null when none (ID-006c). */
    suspend fun createSigner(): space.bitos.core.identity.EventApprovalSigner? = withContext(Dispatchers.IO) {
        val active = registry.activePubkey.value
            ?: registry.accounts.value.firstOrNull()?.pubkeyHex
        val row = registry.accounts.value.firstOrNull { it.pubkeyHex == active }
        val kind = row?.signerKind ?: SignerKind.LOCAL_KEY
        when (kind) {
            SignerKind.LOCAL_KEY ->
                (active?.let { store.loadSecret(slotPubkey = it) } ?: store.loadSecret())
                    ?.let { secret ->
                        space.bitos.core.identity.LocalKeyEventSigner(
                            secretHex = secret,
                            random32 = {
                                ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                            },
                        )
                    }
            SignerKind.REMOTE_NIP46 -> {
                // Kill-switch (plan §feature flags): requests are blocked,
                // the account and its drafts are not deleted.
                if (!IdentityFeatureFlags.nip46Enabled || row == null) return@withContext null
                nip46Session(row)()
            }
            SignerKind.EXTERNAL_NIP55 -> {
                if (!IdentityFeatureFlags.nip55Enabled || row == null) return@withContext null
                row.nip55?.let { meta ->
                    space.bitos.app.identity.nip55.Nip55SignerClient(
                        accountPubkeyHex = row.pubkeyHex,
                        pinnedPackage = meta.signerPackage,
                    ).also { _activeNip55Client.value = it }
                }
            }
            SignerKind.READ_ONLY -> null
        }
    }

    /** Live handles for the awaiting-signer UI and ID-006d cleanup. */
    private val _activeNip46Session = kotlinx.coroutines.flow.MutableStateFlow<space.bitos.app.identity.nip46.Nip46SignerSession?>(null)
    val activeNip46Session: kotlinx.coroutines.flow.StateFlow<space.bitos.app.identity.nip46.Nip46SignerSession?> = _activeNip46Session.asStateFlow()

    private val _activeNip55Client = kotlinx.coroutines.flow.MutableStateFlow<space.bitos.app.identity.nip55.Nip55SignerClient?>(null)
    val activeNip55Client: kotlinx.coroutines.flow.StateFlow<space.bitos.app.identity.nip55.Nip55SignerClient?> = _activeNip55Client.asStateFlow()

    /** Builds (and remembers) the NIP-46 session for a registry row. */
    private fun nip46Session(row: space.bitos.core.identity.RegisteredAccount?): () -> space.bitos.app.identity.nip46.Nip46SignerSession? {
        val metadata = row?.nip46 ?: return { null }
        val scope = (getApplication() as? space.bitos.app.BitOsApplication)?.applicationScope ?: viewModelScope
        return {
            space.bitos.app.identity.nip46.Nip46SignerSession(
                scope = scope,
                metadata = metadata,
                accountPubkeyHex = row.pubkeyHex,
                secretStore = space.bitos.app.identity.nip46.SecureNip46TransportSecretStore(store),
                transportFactory = { relay, _ ->
                    space.bitos.app.data.relay.OkHttpRelayTransport(
                        relay, space.bitos.app.data.relay.relayHttpClient(),
                    ) { }
                },
            ).also { _activeNip46Session.value = it }
        }
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
