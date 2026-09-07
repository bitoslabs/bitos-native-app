package space.bitos.app.data.publish

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** Meme PoW mining-window bounds (PowCard parity): chunked so the loop
 *  stays cancellable between windows, hard-capped so a runaway target
 *  can never spin forever. */
private const val MEME_POW_CHUNK_ATTEMPTS = 20_000L
private const val MEME_POW_HARD_ATTEMPT_CAP = 5_000_000L

/** Real meme-publish checkpoints (drives the publish machine stepper):
 * BUILT → SIGNED → RELAYED; the String payload is the canonical event id. */
enum class MemeNoteStage { BUILT, SIGNED, RELAYED }

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
    /** Live supplier: `["client","BitOS"]` rides bitz/media publishes when
     *  the privacy pref allows branding (web clientTag() parity). Read at
     *  publish time so a settings flip applies without a restart. */
    private val includeClientTag: () -> Boolean = { false },
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
     * APP-008 composer page: kind-1 with derived tags (hashtags, NIP-27
     * entities, NIP-36 CW) — `ComposerRules.deriveTags` output.
     */
    fun publishNoteWith(
        content: String,
        tags: List<List<String>>,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val note = composer.composeTextNote(signer.publicKeyHex(), content, tags)
                ?: run {
                    mutableState.value = PublishUiState(result = PublishResult.INVALID)
                    return@launch
                }
            publishUnsigned(note, signer, writeRelays)
        }
    }

    /**
     * APP-006 story like (kind 7 with e/p/a target tags, web `stories.like`
     * parity): tags come from `StoriesInteractions.targetTags`.
     */
    fun publishReactionWithTags(
        emoji: String,
        tags: List<List<String>>,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val reaction = composer.composeReactionWithTags(signer.publicKeyHex(), emoji, tags)
                ?: run {
                    mutableState.value = PublishUiState(result = PublishResult.INVALID)
                    return@launch
                }
            publishUnsigned(reaction, signer, writeRelays)
        }
    }

    /**
     * APP-006 story publish (web `stories.publish` parity): kind-30315 with
     * per-image NIP-92 imeta, an optional video imeta, background gradient
     * for text-only slides and a content-warning tag for sensitive media.
     */
    fun publishStory(
        text: String,
        imageUrls: List<String>,
        background: String?,
        altText: String?,
        sensitive: Boolean,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
        videoUrl: String? = null,
        videoMime: String? = null,
        videoDurationMs: Long? = null,
        videoPoster: String? = null,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val nowSeconds = System.currentTimeMillis() / 1000
            val dTag = space.bitos.core.model.Stories.storyDTag(nowSeconds, (1..Int.MAX_VALUE).random())
            val story = composer.composeStory(
                signer.publicKeyHex(), text, imageUrls, background, altText, sensitive, dTag,
                videoUrl, videoMime, videoDurationMs, videoPoster,
            ) ?: run {
                mutableState.value = PublishUiState(result = PublishResult.INVALID)
                return@launch
            }
            publishUnsigned(story, signer, writeRelays)
        }
    }

    /**
     * APP-006 story PoW publish: the nonce was mined over the exact
     * kind-30315 template (same `dTag` + `createdAtSeconds` the PowCard
     * session committed), so the published event reproduces the mined id.
     */
    fun publishStoryWithPow(
        text: String,
        imageUrls: List<String>,
        background: String?,
        altText: String?,
        sensitive: Boolean,
        dTag: String,
        nonce: Long,
        targetDifficulty: Int,
        createdAtSeconds: Long,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
        videoUrl: String? = null,
        videoMime: String? = null,
        videoDurationMs: Long? = null,
        videoPoster: String? = null,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val story = composer.composeStoryWithPow(
                signer.publicKeyHex(), text, imageUrls, background, altText, sensitive, dTag,
                nonce, targetDifficulty, createdAtSeconds, videoUrl, videoMime, videoDurationMs, videoPoster,
            ) ?: run {
                mutableState.value = PublishUiState(result = PublishResult.INVALID)
                return@launch
            }
            publishUnsigned(story, signer, writeRelays)
        }
    }

    /**
     * NIP-22 kind-1111 comment on a non-kind-1 event (web `feed.comment`
     * parity): tags come from `NoteComposer.commentTags`.
     */
    fun publishCommentWith(
        content: String,
        tags: List<List<String>>,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val comment = composer.composeCommentWithTags(signer.publicKeyHex(), content, tags)
                ?: run {
                    mutableState.value = PublishUiState(result = PublishResult.INVALID)
                    return@launch
                }
            publishUnsigned(comment, signer, writeRelays)
        }
    }

    /**
     * Comment-sheet PoW path: kind-1111 comment with a pre-mined nonce tag
     * (the mining template carried the same `commentTags` — PowCard
     * `baseTags`).
     */
    fun publishPowCommentWith(
        content: String,
        tags: List<List<String>>,
        nonce: Long,
        targetDifficulty: Int,
        createdAtSeconds: Long,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val comment = composer.composeCommentWithPow(
                signer.publicKeyHex(), content, nonce, targetDifficulty, createdAtSeconds, tags,
            ) ?: run {
                mutableState.value = PublishUiState(result = PublishResult.INVALID)
                return@launch
            }
            publishUnsigned(comment, signer, writeRelays)
        }
    }

    /**
     * APP-008 composer page PoW path: pre-mined nonce + derived tags (the
     * mining template included the same tags — PowCard `baseTags`).
     */
    fun publishPowNoteWith(
        content: String,
        tags: List<List<String>>,
        nonce: Long,
        targetDifficulty: Int,
        createdAtSeconds: Long,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val note = composer.composeTextNoteWithPow(
                signer.publicKeyHex(), content, nonce, targetDifficulty, createdAtSeconds, tags,
            ) ?: run {
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

    /**
     * Kind-10002 relay-list publish (NIP-65, APP-018 relays manager):
     * composes from the managed-set wire JSON and fans out to the set's
     * write-role relays through the same receipt machine.
     */
    fun publishRelayList(
        relayListJson: String,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val list = composer.composeRelayList(
                signer.publicKeyHex(),
                space.bitos.core.model.RelayListContract.decode(relayListJson),
            ) ?: run {
                mutableState.value = PublishUiState(result = PublishResult.INVALID)
                return@launch
            }
            publishUnsigned(list, signer, writeRelays)
        }
    }

    /**
     * Kind-10004 public block-list publish (NIP-51, APP-018 privacy):
     * replaces the head with the given set (unblock = publish without).
     */
    fun publishBlockList(
        blocked: List<String>,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val list = composer.composeBlockList(signer.publicKeyHex(), blocked)
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
        altText: String = "",
        contentWarningReason: String? = null,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val note = composer.composeMediaNote(
                signer.publicKeyHex(), caption, media, altText, contentWarningReason,
                includeClientTag = includeClientTag(),
            )
                ?: run {
                    mutableState.value = PublishUiState(result = PublishResult.INVALID)
                    return@launch
                }
            publishUnsigned(note, signer, writeRelays)
        }
    }

    /**
     * Video meme from a verified upload (MST-034): kind 22 portrait / 21
     * landscape through the same receipt machine. Media MUST come from a
     * hash-verified upload (same contract as the other media paths).
     */
    fun publishMemeVideoNote(
        caption: String,
        altText: String,
        contentWarningReason: String?,
        portrait: Boolean,
        media: space.bitos.core.model.UploadedMedia,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
        extraTags: List<List<String>> = emptyList(),
        powBits: Int = 0,
        onStage: ((MemeNoteStage, String) -> Unit)? = null,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val note = if (powBits > 0) {
                composeMemeVideoWithMinedPow(
                    signer.publicKeyHex(), caption, altText, contentWarningReason,
                    portrait, media, extraTags, powBits,
                )
            } else {
                composer.composeMemeVideoNote(
                    signer.publicKeyHex(), caption, altText, contentWarningReason, portrait, media, extraTags,
                    includeClientTag = includeClientTag(),
                )
            } ?: run {
                mutableState.value = PublishUiState(result = PublishResult.INVALID)
                return@launch
            }
            onStage?.invoke(MemeNoteStage.BUILT, note.idHex)
            publishUnsigned(note, signer, writeRelays, onStage)
        }
    }

    /**
     * Post-upload meme PoW (MST post-details, `composeStoryWithPow`
     * contract): mine the EXACT kind-22/21 template (media is uploaded —
     * the imeta is final) in bounded cancellable chunks on Dispatchers
     * .Default, then compose with the nonce so the published id
     * byte-matches the miner's serialization. null = invalid template or
     * an exhausted window (surface "try fewer bits", never mine forever).
     */
    private suspend fun composeMemeVideoWithMinedPow(
        pubkey: String,
        caption: String,
        altText: String,
        contentWarningReason: String?,
        portrait: Boolean,
        media: space.bitos.core.model.UploadedMedia,
        extraTags: List<List<String>>,
        powBits: Int,
    ): space.bitos.core.publish.UnsignedNote? {
        val minedAt = System.currentTimeMillis() / 1000
        val powComposer = NoteComposer(clock = { minedAt })
        val base = powComposer.composeMemeVideoNote(
            pubkey, caption, altText, contentWarningReason, portrait, media, extraTags,
            includeClientTag = includeClientTag(),
        ) ?: return null
        val mined = mineMemePowWindow(base, powBits) ?: run {
            mutableState.value = PublishUiState(result = PublishResult.TIMEOUT)
            return null
        }
        return powComposer.composeMemeVideoNoteWithPow(
            pubkey, caption, altText, contentWarningReason, portrait, media, extraTags,
            includeClientTag = includeClientTag(),
            nonce = mined.nonce, targetDifficulty = powBits, createdAtSeconds = minedAt,
        )
    }

    /** Chunked mining driver (PowCard bounds): ≤5M attempts total. */
    private suspend fun mineMemePowWindow(
        base: space.bitos.core.publish.UnsignedNote,
        targetDifficulty: Int,
    ): space.bitos.core.nostr.Pow.MinedAttempt? {
        var startNonce = 0L
        var attempted = 0L
        while (attempted < MEME_POW_HARD_ATTEMPT_CAP) {
            val mined = withContext(Dispatchers.Default) {
                space.bitos.core.nostr.Pow.mineChunk(
                    space.bitos.core.nostr.Sha256EventHasher,
                    base.pubkeyHex, base.createdAtSeconds, base.kind, base.tags, base.content,
                    targetDifficulty, startNonce, MEME_POW_CHUNK_ATTEMPTS,
                )
            }
            if (mined != null) return mined
            startNonce += MEME_POW_CHUNK_ATTEMPTS
            attempted += MEME_POW_CHUNK_ATTEMPTS
        }
        return null
    }

    /**
     * Kind-20 picture meme from a verified upload (MST-017): web tag order
     * (t-tags, alt, imeta, CW) → sign → receipt machine. The media MUST
     * come from a hash-verified Blossom upload — the uploader enforces that
     * before this is reachable (same contract as the kind-22 path).
     */
    fun publishMemePictureNote(
        caption: String,
        altText: String,
        contentWarningReason: String?,
        media: space.bitos.core.model.UploadedMedia,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
        extraTags: List<List<String>> = emptyList(),
        powBits: Int = 0,
        onStage: ((MemeNoteStage, String) -> Unit)? = null,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val note = if (powBits > 0) {
                composeMemePictureWithMinedPow(
                    signer.publicKeyHex(), caption, altText, contentWarningReason, media, extraTags, powBits,
                )
            } else {
                composer.composeMemePictureNote(
                    signer.publicKeyHex(), caption, altText, contentWarningReason, media, extraTags,
                    includeClientTag = includeClientTag(),
                )
            } ?: run {
                mutableState.value = PublishUiState(result = PublishResult.INVALID)
                return@launch
            }
            onStage?.invoke(MemeNoteStage.BUILT, note.idHex)
            publishUnsigned(note, signer, writeRelays, onStage)
        }
    }

    /** Kind-20 twin of [composeMemeVideoWithMinedPow]. */
    private suspend fun composeMemePictureWithMinedPow(
        pubkey: String,
        caption: String,
        altText: String,
        contentWarningReason: String?,
        media: space.bitos.core.model.UploadedMedia,
        extraTags: List<List<String>>,
        powBits: Int,
    ): space.bitos.core.publish.UnsignedNote? {
        val minedAt = System.currentTimeMillis() / 1000
        val powComposer = NoteComposer(clock = { minedAt })
        val base = powComposer.composeMemePictureNote(
            pubkey, caption, altText, contentWarningReason, media, extraTags,
            includeClientTag = includeClientTag(),
        ) ?: return null
        val mined = mineMemePowWindow(base, powBits) ?: run {
            mutableState.value = PublishUiState(result = PublishResult.TIMEOUT)
            return null
        }
        return powComposer.composeMemePictureNoteWithPow(
            pubkey, caption, altText, contentWarningReason, media, extraTags,
            includeClientTag = includeClientTag(),
            nonce = mined.nonce, targetDifficulty = powBits, createdAtSeconds = minedAt,
        )
    }

    /** Kind-0 profile metadata publish through the receipt machine. */
    fun publishProfile(
        name: String,
        displayName: String,
        about: String,
        picture: String,
        nip05: String,
        lud16: String,
        banner: String = "",
        website: String = "",
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
                authorPubkey = signer.publicKeyHex(),
                name = name, displayName = displayName, about = about,
                picture = picture, nip05 = nip05, lud16 = lud16,
                banner = banner, website = website,
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

    /** Kind-1018 poll vote publish (web `votePoll` wire parity). */
    fun publishPollVote(
        targetEventId: String,
        optionIndex: Int,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val vote = composer.composePollVote(targetEventId, optionIndex, signer.publicKeyHex())
                ?: run {
                    mutableState.value = PublishUiState(result = PublishResult.INVALID)
                    return@launch
                }
            publishUnsigned(vote, signer, writeRelays)
        }
    }

    /** Kind-5 deletion publish (NIP-09, web `feed.deleteNote` parity). */
    fun publishDeletion(
        targetEventIds: List<String>,
        reason: String = "Deleted from BitOS",
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val deletion = composer.composeDeletion(targetEventIds, signer.publicKeyHex(), reason)
                ?: run {
                    mutableState.value = PublishUiState(result = PublishResult.INVALID)
                    return@launch
                }
            publishUnsigned(deletion, signer, writeRelays)
        }
    }

    /** NIP-51 interest set publish (kind 30015 d=interest — followed hashtags). */
    fun publishInterestSet(
        hashtags: List<String>,
        signerProvider: suspend () -> IdentitySigner?,
        writeRelays: List<RelayUrl>,
    ) {
        if (mutableState.value.result != null || mutableState.value.inFlightId != null) return
        scope.launch {
            val signer = signerProvider() ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return@launch
            }
            val list = composer.composeInterestSet(signer.publicKeyHex(), hashtags)
                ?: run {
                    mutableState.value = PublishUiState(result = PublishResult.INVALID)
                    return@launch
                }
            publishUnsigned(list, signer, writeRelays)
        }
    }

    private suspend fun publishUnsigned(
        note: space.bitos.core.publish.UnsignedNote,
        signer: IdentitySigner,
        writeRelays: List<RelayUrl>,
        onStage: ((MemeNoteStage, String) -> Unit)? = null,
    ) {
        val signature = signer.sign(note.messageBytes())
            ?: run {
                mutableState.value = PublishUiState(result = PublishResult.SIGNING_REFUSED)
                return
            }
        onStage?.invoke(MemeNoteStage.SIGNED, note.idHex)
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
            onStage?.invoke(MemeNoteStage.RELAYED, note.idHex)
            // Card actions share this publisher with the full composer. A
            // terminal receipt is useful feedback, but it must not leave the
            // action rail permanently unable to publish its next mutation.
            // Keep it long enough for UI observers, then return to idle.
            val terminalResult = mutableState.value.result
            scope.launch {
                delay(1_500)
                val current = mutableState.value
                if (current.inFlightId == null && current.result == terminalResult) {
                    mutableState.value = PublishUiState()
                }
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
