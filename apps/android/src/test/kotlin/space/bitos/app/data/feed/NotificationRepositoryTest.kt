package space.bitos.app.data.feed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import space.bitos.app.data.relay.RelayConnectionState
import space.bitos.app.data.relay.RelayFrame
import space.bitos.app.data.relay.RelayPool
import space.bitos.app.data.relay.RelayTransport
import space.bitos.core.identity.DeterministicTestSigner
import space.bitos.core.model.BlockList
import space.bitos.core.model.NotificationKind
import space.bitos.core.model.RelayUrl
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.publish.NoteComposer
import space.bitos.core.publish.PublishClock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * APP-012 adapter-contract test: the notification repository extracts
 * verified events (incl. kind-3 follows), dedupes republished follows,
 * persists read state through its prefs port, and fills/ages origin-note
 * previews. All frames are signed in-test with the deterministic test
 * signer (public test keys only).
 */
class NotificationRepositoryTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val relay = RelayUrl.parse("wss://relay.test")!!
    private val hasher = Sha256EventHasher

    private val account = DeterministicTestSigner("01".repeat(32), hasher)
    private val actor1 = DeterministicTestSigner("02".repeat(32), hasher)
    private val actor2 = DeterministicTestSigner("03".repeat(32), hasher)
    private val composer = NoteComposer(hasher, PublishClock { 1_700_000_000L })

    private lateinit var transport: FakeNotificationTransport
    private lateinit var pool: RelayPool
    private lateinit var prefs: RecordingPrefs
    private lateinit var repository: NotificationRepository

    @BeforeTest
    fun setUp() {
        transport = FakeNotificationTransport(relay)
        pool = RelayPool(scope, listOf(relay)) { _, _ -> transport }
        prefs = RecordingPrefs()
        repository = NotificationRepository(
            scope = scope,
            pool = pool,
            hasher = hasher,
            prefs = prefs,
            originTimeoutMillis = 150,
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    /** Signed relay frame (3-element form) for an unsigned note. */
    private fun relayFrame(unsigned: space.bitos.core.publish.UnsignedNote, signer: DeterministicTestSigner): String {
        val signature = runBlocking { signer.sign(unsigned.messageBytes()) }!!
        val clientFrame = composer.publishMessage(unsigned, signature)!!
        return """["EVENT","sub",""" + clientFrame.substringAfter(',')
    }

    /** Hand-built event with an explicit created-at (the composer clock is fixed). */
    private fun unsignedEvent(
        kind: Int,
        tags: List<List<String>>,
        content: String,
        authorPubkey: String,
        createdAt: Long,
    ): space.bitos.core.publish.UnsignedNote = space.bitos.core.publish.UnsignedNote(
        idHex = NostrEventCodec.computeId(hasher, authorPubkey, createdAt, kind, tags, content),
        pubkeyHex = authorPubkey,
        createdAtSeconds = createdAt,
        kind = kind,
        tags = tags,
        content = content,
    )

    /** APP-012 blocked-author filtering: signed kind-10004 head evicts rows,
     * filters new arrivals and rides the subscription round. */
    @Test
    fun blockListHeadEvictsRowsAndFiltersArrivals(): Unit = runBlocking {
        repository.setAccount(account.publicKeyHex())
        withTimeout(20_000) { repository.state.first { it.hasAccount } }
        val target = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"

        transport.emit(relayFrame(composer.composeReaction(target, account.publicKeyHex(), actor1.publicKeyHex())!!, actor1))
        withTimeout(20_000) { repository.state.first { it.items.size == 1 } }

        // The account publishes a block list naming actor1 (newest head wins).
        val blockTags = listOf(listOf("p", actor1.publicKeyHex()))
        val blockListEvent = unsignedEvent(BlockList.KIND, blockTags, "", account.publicKeyHex(), 1_700_000_500)
        transport.emit(relayFrame(blockListEvent, account))
        val blocked = withTimeout(20_000) { repository.state.first { it.blockedPubkeys.isNotEmpty() } }
        assertEquals(setOf(actor1.publicKeyHex()), blocked.blockedPubkeys)
        assertTrue(blocked.items.isEmpty(), "blocked author's row must be evicted")

        // New arrivals from the blocked author never surface; others do.
        transport.emit(relayFrame(composer.composeReply("blocked hello", target, account.publicKeyHex(), actor1.publicKeyHex())!!, actor1))
        kotlinx.coroutines.delay(200)
        assertTrue(repository.state.value.items.none { it.authorPubkey == actor1.publicKeyHex() })
        transport.emit(relayFrame(composer.composeReply("allowed hello", target, account.publicKeyHex(), actor2.publicKeyHex())!!, actor2))
        withTimeout(20_000) { repository.state.first { it.items.size == 1 } }

        // The kind-10004 REQ went out with the subscription round.
        assertTrue(transport.sent.any { it.contains("bitos-blocks") && it.contains("10004") })
    }

    /** APP-012 read cursor: marking a fresh item read covers redelivered
     * history (never re-rings), and the cursor survives a store reload. */
    @Test
    fun readCursorCoversRedeliveredHistoryAndPersists(): Unit = runBlocking {
        repository.setAccount(account.publicKeyHex())
        withTimeout(20_000) { repository.state.first { it.hasAccount } }
        val target = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
        val pTag = listOf("p", account.publicKeyHex())

        val oldEvent = unsignedEvent(7, listOf(listOf("e", target), pTag), "", actor1.publicKeyHex(), 1_600_000_000)
        val freshEvent = unsignedEvent(7, listOf(listOf("e", target), pTag), "", actor2.publicKeyHex(), 1_700_000_000)
        transport.emit(relayFrame(oldEvent, actor1))
        transport.emit(relayFrame(freshEvent, actor2))
        withTimeout(20_000) { repository.state.first { it.items.size == 2 } }

        // Mark only the FRESH item read: the cursor advances to its created-at.
        repository.markRead(listOf(freshEvent.idHex))
        val read = withTimeout(20_000) { repository.state.first { it.readIds.size == 2 } }
        assertTrue(oldEvent.idHex in read.readIds, "history at/below the cursor must read implicitly")
        assertEquals(1_700_000_000L, prefs.savedCursor)

        // Store reload (fresh repository, same prefs): redelivered history
        // arrives already read — the badge never re-rings.
        val reloaded = NotificationRepository(scope, pool, hasher, prefs)
        reloaded.setAccount(account.publicKeyHex())
        withTimeout(20_000) { reloaded.state.first { it.hasAccount } }
        transport.emit(relayFrame(oldEvent, actor1))
        val redelivered = withTimeout(20_000) { reloaded.state.first { it.items.size == 1 } }
        assertTrue(oldEvent.idHex in redelivered.readIds)
    }

    @Test
    fun extractsReactionsFollowsAndDedupesRepublishedFollows() = runBlocking {
        repository.setAccount(account.publicKeyHex())
        withTimeout(20_000) { repository.state.first { it.hasAccount } }

        val target = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"

        transport.emit(relayFrame(composer.composeReaction(target, account.publicKeyHex(), actor1.publicKeyHex())!!, actor1))
        transport.emit(relayFrame(composer.composeReaction(target, account.publicKeyHex(), actor2.publicKeyHex())!!, actor2))
        transport.emit(relayFrame(composer.composeReply("nice one", target, account.publicKeyHex(), actor1.publicKeyHex())!!, actor1))
        transport.emit(relayFrame(composer.composeFollowList(actor1.publicKeyHex(), listOf(account.publicKeyHex()))!!, actor1))
        // Republished follow from the same author is dropped (kind-3 spam).
        transport.emit(relayFrame(composer.composeFollowList(actor1.publicKeyHex(), listOf(account.publicKeyHex()))!!, actor1))
        // A different author's follow is kept.
        transport.emit(relayFrame(composer.composeFollowList(actor2.publicKeyHex(), listOf(account.publicKeyHex()))!!, actor2))

        val state = withTimeout(20_000) { repository.state.first { it.items.size == 5 } }
        assertEquals(2, state.items.count { it.kind == NotificationKind.REACTION })
        assertEquals(1, state.items.count { it.kind == NotificationKind.REPLY })
        assertEquals(2, state.items.count { it.kind == NotificationKind.FOLLOW })
        // Raw frames kept per notification id for the raw-JSON row action.
        assertEquals(5, state.rawEvents.size)
        assertTrue(state.rawEvents.values.all { it.startsWith("[\"EVENT\",\"sub\",") }, state.rawEvents.values.joinToString("\n"))
    }

    @Test
    fun markReadFlowsThroughThePrefsPort() = runBlocking {
        repository.setAccount(account.publicKeyHex())
        withTimeout(20_000) { repository.state.first { it.hasAccount } }

        val target = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
        transport.emit(relayFrame(composer.composeReaction(target, account.publicKeyHex(), actor1.publicKeyHex())!!, actor1))
        val state = withTimeout(20_000) { repository.state.first { it.items.size == 1 } }

        repository.markRead(state.items.map { it.id })
        val read = withTimeout(20_000) { repository.state.first { it.readIds.isNotEmpty() } }
        assertEquals(state.items.map { it.id }.toSet(), read.readIds)
        assertEquals(read.readIds, prefs.saved)

        // Read state reloads for the same account.
        repository.setAccount(account.publicKeyHex())
        val reloaded = withTimeout(20_000) { repository.state.first { it.hasAccount } }
        assertEquals(read.readIds, reloaded.readIds)
    }

    @Test
    fun originPreviewsFillFromVerifiedFramesAndTimeOutToUnavailable() = runBlocking {
        repository.setAccount(account.publicKeyHex())
        withTimeout(20_000) { repository.state.first { it.hasAccount } }

        val origin = composer.composeTextNote(account.publicKeyHex(), "my note https://cdn.example/i.png")!!
        val missing = "ab".repeat(32)
        repository.requestOrigins(listOf(origin.idHex, missing))

        // One batched ids REQ went out with both ids.
        val request = transport.sent.first { it.contains("\"ids\"") }
        assertTrue(request.contains(origin.idHex))
        assertTrue(request.contains(missing))

        // The origin note fills from a verified frame (excerpt strips the url).
        transport.emit(relayFrame(origin, account))
        val filled = withTimeout(20_000) {
            repository.state.first { it.origins[origin.idHex] is OriginNoteState.Ready }
        }
        val ready = filled.origins[origin.idHex] as OriginNoteState.Ready
        assertEquals("my note", ready.note.excerpt)
        assertEquals("https://cdn.example/i.png", ready.note.thumbUrl)

        // The missing id ages out to Unavailable after the (shortened) timeout.
        val aged = withTimeout(20_000) {
            repository.state.first { it.origins[missing] is OriginNoteState.Unavailable }
        }
        assertTrue(aged.origins[missing] is OriginNoteState.Unavailable)

        // Unverified (tampered) frames never fill a preview.
        val tampered = relayFrame(origin, account).replace("my note", "tampered note")
        // id mismatch → decode reject; Loading state would stay if still pending.
        repository.requestOrigins(listOf("cd".repeat(32)))
        transport.emit(tampered)
        // No crash and no Ready for the tampered id.
        assertTrue(repository.state.value.origins["cd".repeat(32)] is OriginNoteState.Loading)
    }

    @Test
    fun mutedKindsAreExcludedEvictedAndPersisted(): Unit = runBlocking {
        repository.setAccount(account.publicKeyHex())
        withTimeout(20_000) { repository.state.first { it.hasAccount } }

        val target = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
        transport.emit(relayFrame(composer.composeReaction(target, account.publicKeyHex(), actor1.publicKeyHex())!!, actor1))
        withTimeout(20_000) { repository.state.first { it.items.size == 1 } }

        // Muting reactions evicts collected items and persists the choice.
        repository.setMutedKinds(setOf(NotificationKind.REACTION))
        val muted = withTimeout(20_000) { repository.state.first { it.mutedKinds.isNotEmpty() } }
        assertTrue(muted.items.isEmpty())
        assertEquals(setOf("REACTION"), prefs.savedMuted)

        // Late muted arrivals never land.
        transport.emit(relayFrame(composer.composeReaction(target, account.publicKeyHex(), actor2.publicKeyHex())!!, actor2))
        kotlinx.coroutines.delay(200)
        assertTrue(repository.state.value.items.isEmpty())

        // Unmuting restores collection on redelivery.
        repository.setMutedKinds(emptySet())
        transport.emit(relayFrame(composer.composeReaction(target, account.publicKeyHex(), actor1.publicKeyHex())!!, actor1))
        withTimeout(20_000) { repository.state.first { it.items.size == 1 } }
    }

    @Test
    fun zapReceiptsCarryAmountAndVerifiedSender() = runBlocking {
        repository.setAccount(account.publicKeyHex())
        withTimeout(20_000) { repository.state.first { it.hasAccount } }

        val payer = DeterministicTestSigner("07".repeat(32), hasher)
        val target = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
        val request = composer.composeZapRequest(
            recipientPubkey = account.publicKeyHex(),
            amountMillisats = 21_000,
            relays = listOf("wss://relay.test"),
            lnurlHint = "user@wallet.example",
            comment = "zap!",
            authorPubkey = payer.publicKeyHex(),
            targetEventId = target,
        )!!
        val requestJson = composer.publishMessage(request, payer.sign(request.messageBytes())!!)!!
            .substringAfter(',').removeSuffix("]")
        // 9735 receipts come from the LNURL server (signed here by `actor1`
        // so the frame passes the repo's verified gate; the extractor reads
        // bolt11 + description tags only).
        val unsignedReceipt = space.bitos.core.publish.UnsignedNote(
            idHex = space.bitos.core.nostr.NostrEventCodec.computeId(
                hasher, actor1.publicKeyHex(), 1_700_000_000, 9_735,
                listOf(
                    listOf("p", account.publicKeyHex()),
                    listOf("e", target),
                    listOf("bolt11", "lnbc210n1qpzry9x8gf2tvdw0s3jn54khce6mua7l"),
                    listOf("description", requestJson),
                ),
                "",
            )!!,
            pubkeyHex = actor1.publicKeyHex(),
            createdAtSeconds = 1_700_000_000,
            kind = 9_735,
            tags = listOf(
                listOf("p", account.publicKeyHex()),
                listOf("e", target),
                listOf("bolt11", "lnbc210n1qpzry9x8gf2tvdw0s3jn54khce6mua7l"),
                listOf("description", requestJson),
            ),
            content = "",
        )
        transport.emit(relayFrame(unsignedReceipt, actor1))

        val state = withTimeout(20_000) { repository.state.first { it.items.isNotEmpty() } }
        val zap = state.items.single()
        assertEquals(NotificationKind.ZAP, zap.kind)
        assertEquals(payer.publicKeyHex(), zap.authorPubkey)
        assertEquals(21_000L, zap.amountMsat)
        assertEquals("⚡ 21 sats", zap.summary)
    }

}

private class RecordingPrefs : NotificationPrefs {
    var saved: Set<String> = emptySet()
    var savedMuted: Set<String> = emptySet()
    var savedCursor: Long = -1L
    override fun readIds(): Set<String> = saved
    override fun save(readIds: Set<String>) {
        saved = readIds
    }
    override fun mutedKinds(): Set<String> = savedMuted
    override fun saveMutedKinds(kinds: Set<String>) {
        savedMuted = kinds
    }
    override fun cursorSeconds(): Long = savedCursor
    override fun saveCursorSeconds(seconds: Long) {
        savedCursor = seconds
    }
}

private class FakeNotificationTransport(private val relay: RelayUrl) : RelayTransport {
    private val mutableState = MutableStateFlow(RelayConnectionState.DISCONNECTED)
    // Replay makes emits-before-subscribe deterministic (see FeedRepositoryTest).
    private val mutableFrames = MutableSharedFlow<RelayFrame>(replay = 8, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val state: StateFlow<RelayConnectionState> = mutableState
    override val frames: SharedFlow<RelayFrame> = mutableFrames
    val sent = mutableListOf<String>()

    override fun connect() {
        mutableState.value = RelayConnectionState.CONNECTED
    }

    override fun send(message: String): Boolean {
        sent += message
        return true
    }

    override fun close(code: Int, reason: String) {
        mutableState.value = RelayConnectionState.DISCONNECTED
    }

    fun emit(message: String) {
        mutableFrames.tryEmit(RelayFrame(relay, message))
    }
}
