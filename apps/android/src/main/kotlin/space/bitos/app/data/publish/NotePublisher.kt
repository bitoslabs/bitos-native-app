package space.bitos.app.data.publish

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import space.bitos.app.data.relay.RelayPool
import space.bitos.core.identity.IdentitySigner
import space.bitos.core.model.RelayUrl
import space.bitos.core.publish.NoteComposer
import space.bitos.core.publish.RelayOk
import java.util.concurrent.ConcurrentHashMap

/** One relay's receipt for the in-flight publish (PUB-008). */
data class RelayReceipt(
    val relay: RelayUrl,
    val accepted: Boolean? = null,
    val message: String? = null,
)

data class PublishUiState(
    val inFlightId: String? = null,
    val receipts: List<RelayReceipt> = emptyList(),
    /** Terminal states. One acceptance anywhere is success; UI labels partial coverage. */
    val result: PublishResult? = null,
)

enum class PublishResult { PUBLISHED, REJECTED, TIMEOUT, SIGNING_REFUSED, INVALID }

/**
 * Text-note publish pipeline (PUB-001 note path): compose (canonical ID) →
 * sign (signer refusal fails without sending) → targeted fan-out to write
 * relays → collect `OK` receipts until first acceptance or timeout.
 *
 * The relay echo of our own note returns through the feed subscription and
 * passes the same ID+signature gate as every other event (reconciliation
 * falls out of the verified pipeline).
 */
class NotePublisher(
    private val scope: CoroutineScope,
    private val pool: RelayPool,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val ackTimeoutMs: Long = 10_000,
) {
    private val composer = NoteComposer(clock = clock::invoke)

    private val mutableState = MutableStateFlow(PublishUiState())
    val state: StateFlow<PublishUiState> = mutableState.asStateFlow()

    private var collectJob: Job? = null

    /**
     * Resolves the signer asynchronously (secure-store unlock is suspending)
     * then publishes; a missing signer surfaces as SIGNING_REFUSED.
     */
    fun publishWith(
        content: String,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            publish(content, signer, writeRelays)
        }
    }

    fun publish(content: String, signer: IdentitySigner, writeRelays: List<RelayUrl>) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val note = composer.composeTextNote(signer.publicKeyHex(), content)
                ?: run {
                    mutableState.value = PublishUiState(result = PublishResult.INVALID)
                    return@launch
                }
            publishUnsigned(note, signer, writeRelays)
        }
    }

    /**
     * Kind-1 note with a pre-mined NIP-13 nonce tag (APP-008 PowCard path):
     * `nonce`/`targetDifficulty`/`createdAtSeconds` come from the mining
     * session over the same content — publish reuses the mined timestamp.
     */
    fun publishPowWith(
        content: String,
        nonce: Long,
        targetDifficulty: Int,
        createdAtSeconds: Long,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val note = composer.composeTextNoteWithPow(
                signer.publicKeyHex(), content, nonce, targetDifficulty, createdAtSeconds,
            ) ?: run {
                mutableState.value = PublishUiState(result = PublishResult.INVALID)
                return@launch
            }
            publishUnsigned(note, signer, writeRelays)
        }
    }

    /**
     * Kind-7 reaction through the same machine (SOC-003): optimistic UI
     * stays with the caller; this publishes and reconciles receipts.
     */
    fun publishReactionWith(
        targetEventId: String,
        targetPubkey: String,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val reaction = composer.composeReaction(targetEventId, targetPubkey, signer.publicKeyHex())
                ?: run {
                    mutableState.value = PublishUiState(result = PublishResult.INVALID)
                    return@launch
                }
            publishUnsigned(reaction, signer, writeRelays)
        }
    }

    /**
     * Kind-1 reply (NIP-10) through the same machine (SOC-002). The thread
     * UI appends optimistically; relay echo reconciles through the gate.
     */
    fun publishReplyWith(
        content: String,
        targetEventId: String,
        targetPubkey: String,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val reply = composer.composeReply(content, targetEventId, targetPubkey, signer.publicKeyHex())
                ?: run {
                    mutableState.value = PublishUiState(result = PublishResult.INVALID)
                    return@launch
                }
            publishUnsigned(reply, signer, writeRelays)
        }
    }

    /**
     * Kind-3 contact-list publish (SOC-001 write path). Optimistic state
     * already lives in the repository; the relay echo reconciles it.
     */
    fun publishFollowList(
        follows: List<String>,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val list = composer.composeFollowList(signer.publicKeyHex(), follows)
                ?: run {
                    mutableState.value = PublishUiState(result = PublishResult.INVALID)
                    return@launch
                }
            publishUnsigned(list, signer, writeRelays)
        }
    }

    /** Kind-6 repost (NIP-18) through the same machine. */
    fun publishRepostWith(
        targetEventId: String,
        targetPubkey: String,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val repost = composer.composeRepost(targetEventId, targetPubkey, signer.publicKeyHex())
                ?: run {
                    mutableState.value = PublishUiState(result = PublishResult.INVALID)
                    return@launch
                }
            publishUnsigned(repost, signer, writeRelays)
        }
    }

    /** Kind-30003 bookmark-list publish (NIP-51, addressable head). */
    fun publishBookmarkList(
        eventIds: List<String>,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val list = composer.composeBookmarkList(signer.publicKeyHex(), eventIds)
                ?: run {
                    mutableState.value = PublishUiState(result = PublishResult.INVALID)
                    return@launch
                }
            publishUnsigned(list, signer, writeRelays)
        }
    }

    /**
     * Kind-22 media note from a verified upload: compose imeta descriptor →
     * sign → relay fan-out through the same receipt machine (PUB media path).
     */
    fun publishMediaNote(
        caption: String,
        media: space.bitos.core.model.UploadedMedia,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val note = composer.composeMediaNote(signer.publicKeyHex(), caption, media)
                ?: run {
                    mutableState.value = PublishUiState(result = PublishResult.INVALID)
                    return@launch
                }
            publishUnsigned(note, signer, writeRelays)
        }
    }

    /** Kind-0 profile metadata publish through the receipt machine. */
    fun publishProfile(
        name: String,
        displayName: String,
        about: String,
        picture: String,
        nip05: String,
        lud16: String,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val profile = composer.composeProfileMetadata(
                signer.publicKeyHex(), name, displayName, about, picture, nip05, lud16,
            ) ?: run {
                mutableState.value = PublishUiState(result = PublishResult.INVALID)
                return@launch
            }
            publishUnsigned(profile, signer, writeRelays)
        }
    }

    /** Kind-1984 report publish through the receipt machine (NIP-56). */
    fun publishReport(
        targetEventId: String?,
        targetPubkey: String,
        reason: String,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val report = composer.composeReport(targetEventId, targetPubkey, signer.publicKeyHex(), reason)
                ?: run {
                    mutableState.value = PublishUiState(result = PublishResult.INVALID)
                    return@launch
                }
            publishUnsigned(report, signer, writeRelays)
        }
    }

    private suspend fun publishUnsigned(note: space.bitos.core.publish.UnsignedNote, signer: IdentitySigner, writeRelays: List<RelayUrl>) {
        val signature = signer.sign(note.messageBytes())
            ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return
            }
        val frame = composer.publishMessage(note, signatureHex = signature)
                ?: run {
                    mutableState.value = PublishUiState(result = PublishResult.INVALID)
                    return
                }

            mutableState.value = PublishUiState(
                inFlightId = note.idHex,
                receipts = writeRelays.map { RelayReceipt(it) },
            )
            pool.sendTo(writeRelays, frame)

            val receipts = ConcurrentHashMap<RelayUrl, RelayOk>()
            collectJob = scope.launch {
                pool.frames.collect { relayFrame ->
                    val ok = NoteComposer.parseOkMessage(relayFrame.message) ?: return@collect
                    if (ok.eventId != note.idHex) return@collect
                    receipts[relayFrame.relay] = ok
                    publishReceipts(note.idHex, writeRelays, receipts)
                }
            }
            // First acceptance anywhere completes; a rejection-only timeout surfaces relay reasons.
            val completed = withTimeoutOrNull(ackTimeoutMs) {
                while (receipts.values.none { it.accepted }) delay(100)
                true
            }
            collectJob?.cancel()
            val final = mutableState.value.copy(inFlightId = null)
            mutableState.value = when {
                completed == true -> final.copy(result = PublishResult.PUBLISHED)
                receipts.values.isNotEmpty() -> final.copy(result = PublishResult.REJECTED)
                else -> final.copy(result = PublishResult.TIMEOUT)
            }
            // Keep the receipt collector alive briefly for late ACKs.
            scope.launch {
                delay(2_000)
                collectJob?.cancel()
            }
    }

    private fun publishReceipts(eventId: String, relays: List<RelayUrl>, receipts: ConcurrentHashMap<RelayUrl, RelayOk>) {
        if (mutableState.value.inFlightId != eventId) return
        mutableState.value = mutableState.value.copy(
            receipts = relays.map { relay ->
                val ok = receipts[relay]
                RelayReceipt(relay, ok?.accepted, ok?.message)
            },
        )
    }

    fun dismiss() {
        collectJob?.cancel()
        mutableState.value = PublishUiState()
    }

}
