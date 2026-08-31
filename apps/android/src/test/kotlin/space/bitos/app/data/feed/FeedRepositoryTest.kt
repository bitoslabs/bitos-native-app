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
import space.bitos.core.feed.FeedNote
import space.bitos.core.model.RelayUrl
import space.bitos.core.nostr.Sha256EventHasher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adapter-contract test: the repository must verify event IDs AND signatures
 * through the shared codec before projection. Signed fixture frames come from
 * `contracts/nostr/fixtures/verification-vectors.json` (independently
 * generated with nostr-tools/@noble BIP-340).
 */
class FeedRepositoryTest {

    private val hasher = Sha256EventHasher
    private val relay = RelayUrl.parse("wss://relay.test")!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var transport: FakeRelayTransport
    private lateinit var pool: RelayPool
    private lateinit var cache: RecordingEventCache
    private lateinit var repository: FeedRepository

    @BeforeTest
    fun setUp() {
        transport = FakeRelayTransport(relay)
        pool = RelayPool(scope, listOf(relay)) { _, _ -> transport }
        cache = RecordingEventCache()
        repository = FeedRepository(scope, pool, hasher, cache, bootstrapPollMs = 25)
    }

    @AfterTest
    fun tearDown() {
        repository.stop()
        scope.cancel()
    }

    @Test
    fun verifiesAndProjectsSignedFixtureEvents() = runBlocking {
        repository.start()
        transport.emit(VALID_TEXT_NOTE_MESSAGE)
        transport.emit(VALID_SECOND_KEY_MESSAGE)
        val state = withTimeout(20_000) { repository.state.first { it.notes.size == 2 } }
        assertEquals(
            listOf("gm from BitOS", "second author note").sorted(),
            state.notes.map { it.content }.sorted(),
        )
        assertEquals(1, state.relayHealth.total)
    }

    /**
     * Live arrivals hold after the initial relay snapshot, reveal prepends
     * them, and relay redelivery never duplicates a pending or inserted note.
     */
    @Test
    fun heldArrivalsWaitForRevealAndRedeliveryNeverDuplicates(): Unit = runBlocking {
        repository.start()
        withTimeout(20_000) {
            while (transport.sent.none { it.contains("bitos-feed-1") }) kotlinx.coroutines.delay(10)
        }
        transport.emit(VALID_TEXT_NOTE_MESSAGE)
        withTimeout(20_000) { repository.state.first { it.notes.size == 1 } }

        transport.emit("""["EOSE","bitos-feed-1"]""")
        transport.emit(VALID_SECOND_KEY_MESSAGE)
        val held = withTimeout(20_000) { repository.state.first { it.pendingNotes.size == 1 } }
        assertEquals(1, held.notes.size, "window must not jump while holding")

        transport.emit(VALID_SECOND_KEY_MESSAGE) // relay redelivery
        assertEquals(1, repository.state.value.pendingNotes.size)

        repository.revealPendingNotes()
        val revealed = withTimeout(20_000) { repository.state.first { it.notes.size == 2 } }
        assertEquals(0, revealed.pendingNotes.size)
        assertEquals(2, revealed.notes.map { it.id }.toSet().size)

        // Returning to the top does not flush later arrivals.
        transport.emit(VALID_SECOND_KEY_MESSAGE)
        withTimeout(20_000) { repository.state.first { it.notes.size == 2 && it.pendingNotes.isEmpty() } }
    }

    @Test
    fun liveArrivalsAfterInitialEoseOnlyUpdateThePendingPill(): Unit = runBlocking {
        repository.start()
        withTimeout(20_000) {
            while (transport.sent.none { it.contains("bitos-feed-1") }) kotlinx.coroutines.delay(10)
        }
        transport.emit(VALID_TEXT_NOTE_MESSAGE)
        withTimeout(20_000) { repository.state.first { it.notes.size == 1 } }

        // The persistent head is now live. No scroll-position signal is
        // required: a reader at the top must still get a stable timeline.
        transport.emit("""["EOSE","bitos-feed-1"]""")
        transport.emit(VALID_SECOND_KEY_MESSAGE)
        val pending = withTimeout(20_000) {
            repository.state.first { it.notes.size == 1 && it.pendingNotes.size == 1 }
        }
        assertEquals("second author note", pending.pendingNotes.single().content)

        repository.revealPendingNotes()
        withTimeout(20_000) { repository.state.first { it.notes.size == 2 && it.pendingNotes.isEmpty() } }
    }

    @Test
    fun pendingBufferCanDrainWhileAnotherThreadAddsAnArrival() {
        val pending = PendingFeedNotes(maxItems = 100)
        val executor = Executors.newFixedThreadPool(2)
        try {
            pending.add(FeedTimeline.FOR_YOU, testNote("initial"))
            val receivedIds = mutableSetOf<String>()
            repeat(500) { index ->
                val start = CountDownLatch(1)
                val drain = executor.submit<List<FeedNote>> {
                    start.await()
                    pending.drain(FeedTimeline.FOR_YOU)
                }
                val add = executor.submit {
                    start.await()
                    pending.add(FeedTimeline.FOR_YOU, testNote("arrival-$index"))
                }
                start.countDown()
                receivedIds += drain.get().map(FeedNote::id)
                add.get()
            }

            receivedIds += pending.drain(FeedTimeline.FOR_YOU).map(FeedNote::id)
            assertEquals(
                (0 until 500).map { "arrival-$it" }.toSet() + "initial",
                receivedIds,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * APP-004: empty-feed auto-retry — while connected and empty the repo
     * re-issues its REQ on the (injected, fast) backoff; a verified note
     * arriving stops further retries (attempt resets, loop idles).
     */
    @Test
    fun emptyFeedAutoRetryResubscribesAndResetsOnArrival(): Unit = runBlocking {
        val fastRepo = FeedRepository(scope, pool, hasher, cache, retryDelayMs = { 10 })
        fastRepo.start()
        try {
            // Fake transport reports CONNECTED on start, so the gate passes:
            // retries accumulate while the window stays empty.
            withTimeout(20_000) {
                while (transport.sent.count { it.contains("bitos-feed") } < 3) {
                    kotlinx.coroutines.delay(10)
                }
            }

            // A verified note arrives; the loop must stop re-subscribing.
            transport.emit(VALID_TEXT_NOTE_MESSAGE)
            withTimeout(20_000) { fastRepo.state.first { it.notes.isNotEmpty() } }
            val settled = transport.sent.count { it.contains("bitos-feed") }
            kotlinx.coroutines.delay(300) // many loop cycles
            assertEquals(settled, transport.sent.count { it.contains("bitos-feed") })
        } finally {
            fastRepo.stop()
        }
    }

    /**
     * APP-004 pagination: loadOlder issues an `until` REQ pinned to the
     * window's oldest note; an in-flight page blocks duplicate REQs.
     */
    @Test
    fun loadOlderRequestsUntilOldestAndDedupesInFlight(): Unit = runBlocking {
        repository.start()
        transport.emit(VALID_TEXT_NOTE_MESSAGE)
        transport.emit(VALID_SECOND_KEY_MESSAGE)
        withTimeout(20_000) { repository.state.first { it.notes.size == 2 } }

        repository.loadOlder()
        withTimeout(20_000) {
            while (transport.sent.none { it.contains("bitos-older-1") && it.contains("\"until\":1709999999") }) {
                kotlinx.coroutines.delay(10)
            }
        }
        val request = transport.sent.last { it.contains("bitos-older-1") }
        // Walk filters (shared BitzTimelinePolicy): deep media kinds with a
        // 60-limit + shallow kind-1 150, cursor = oldest − 1 (until exclusive).
        assertTrue(request.contains("\"kinds\":[20,21,22,34235,34236],\"limit\":60,\"until\":1709999999"), request)
        assertTrue(request.contains("\"kinds\":[1],\"limit\":150,\"until\":1709999999"), request)
        assertTrue(repository.state.value.isLoadingOlder)

        // In-flight guard: a second call must not issue another REQ.
        val olderReqs = transport.sent.count { it.contains("bitos-older") }
        repository.loadOlder()
        kotlinx.coroutines.delay(200)
        assertEquals(olderReqs, transport.sent.count { it.contains("bitos-older") })
    }

    @Test
    fun olderPageBypassesTheLiveArrivalHold(): Unit = runBlocking {
        repository.start()
        transport.emit(VALID_TEXT_NOTE_MESSAGE)
        withTimeout(20_000) { repository.state.first { it.notes.size == 1 } }
        repository.holdNewNotes(true)

        transport.emit(VALID_SECOND_KEY_MESSAGE.replace("\"sub1\"", "\"bitos-older-1\""))

        val state = withTimeout(20_000) { repository.state.first { it.notes.size == 2 } }
        assertTrue(state.pendingNotes.isEmpty())
    }

    @Test
    fun olderBatchUsesItsOwnOldestEventAndEoseCompletesWithoutDeadlineWait(): Unit = runBlocking {
        // Cursor and EOSE wire values mirror pagination-v1.json; signed EVENT
        // frames remain verbatim verification-vectors.json fixtures.
        repository.start()
        // Head is 1710000300, so the first request starts at 1710000299.
        transport.emit(VALID_SECOND_KEY_MESSAGE)
        withTimeout(20_000) { repository.state.first { it.notes.size == 1 } }
        repository.loadOlder()
        withTimeout(2_000) {
            while (transport.sent.none { it.contains("bitos-older-1") }) kotlinx.coroutines.delay(10)
        }

        // This exact batch returns an older verified note. EOSE must advance
        // immediately to oldest-created-at - 1, not wait the four-second
        // watchdog and not inspect an unrelated global-window boundary.
        transport.emit(VALID_TEXT_NOTE_MESSAGE.replace("\"sub1\"", "\"bitos-older-1\""))
        transport.emit("""["EOSE","bitos-older-1"]""")

        withTimeout(2_000) {
            while (transport.sent.none {
                    it.contains("bitos-older-2") && it.contains("\"until\":1709999999")
                }) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertTrue(transport.sent.any { it == """["CLOSE","bitos-older-1"]""" })
    }

    @Test
    fun dropsSignatureUnverifiedAndUnsignedEvents() = runBlocking {
        val unsigned = """["EVENT","sub1",{"id":"${"0".repeat(64)}","pubkey":"${"aa".repeat(32)}","created_at":1710000900,"kind":1,"tags":[],"content":"unsigned"}]"""
        repository.start()
        transport.emit(VALID_ID_WRONG_SIGNATURE_MESSAGE)
        transport.emit(unsigned)
        transport.emit(VALID_TEXT_NOTE_MESSAGE)
        val state = withTimeout(20_000) { repository.state.first { it.hasLoadedAnyEvent } }
        assertEquals(listOf("gm from BitOS"), state.notes.map { it.content })
    }

    @Test
    fun persistsVerifiedEventsAndHydratesOnRestart() = runBlocking {
        repository.start()
        transport.emit(VALID_TEXT_NOTE_MESSAGE)
        withTimeout(20_000) { repository.state.first { it.hasLoadedAnyEvent } }
        // Persisted exactly once (dedupe by id at the aggregator is separate
        // from cache writes: one write per absorbed verified event).
        withTimeout(20_000) {
            while (cache.stored.size != 1) kotlinx.coroutines.delay(10)
        }
        assertEquals("gm from BitOS", cache.stored.single().content)

        // Simulate process restart: new repository, same cache.
        repository.stop()
        val restarted = FeedRepository(scope, pool, hasher, cache)
        restarted.start()
        val hydrated = withTimeout(20_000) { restarted.state.first { it.notes.isNotEmpty() } }
        assertEquals(listOf("gm from BitOS"), hydrated.notes.map { it.content })
    }

    @Test
    fun buildsFollowingTimelineFromContactList() = runBlocking {
        val account = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
        val followedAuthor = "e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301"
        repository.start()
        repository.setAccount(account)
        transport.emit(VALID_CONTACT_LIST_MESSAGE)
        withTimeout(20_000) { repository.state.first { it.followingResolved } }

        // A following REQ targeted the followed author.
        withTimeout(20_000) {
            while (transport.sent.none { it.contains("bitos-following") && it.contains(followedAuthor) }) {
                kotlinx.coroutines.delay(10)
            }
        }

        // Notes from the followed author land in the Following window; others do not.
        repository.selectTimeline(space.bitos.app.data.feed.FeedTimeline.FOLLOWING)
        transport.emit(VALID_SECOND_KEY_MESSAGE)
        transport.emit(VALID_TEXT_NOTE_MESSAGE)
        val state = withTimeout(20_000) { repository.state.first { it.notes.isNotEmpty() } }
        assertEquals(listOf("second author note"), state.notes.map { it.content })
    }

    @Test
    fun loadsAndGroupsRepliesByTarget() = runBlocking {
        repository.start()
        transport.emit(VALID_TEXT_NOTE_MESSAGE)
        withTimeout(20_000) { repository.state.first { it.hasLoadedAnyEvent } }

        // Open the thread for the text note; a #e-tagged REQ goes out.
        val targetId = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
        repository.loadComments(targetId)
        withTimeout(20_000) {
            while (transport.sent.none { it.contains("bitos-comments") && it.contains(targetId) }) {
                kotlinx.coroutines.delay(10)
            }
        }

        // A signed reply arrives; it lands in the thread (and the main window).
        val reply = """["EVENT","sub1",{"kind":1,"created_at":1710000500,"tags":[["e","$targetId","","reply"],["p","2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"]],"content":"first reply","pubkey":"e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301","id":"ID_PLACEHOLDER","sig":"SIG_PLACEHOLDER"}]"""
        // Build a verified reply deterministically from the fixture key.
        val signer = space.bitos.core.identity.DeterministicTestSigner("4b1aa1a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d")
        val composer = space.bitos.core.publish.NoteComposer(clock = { 1_710_000_500 })
        val unsigned = composer.composeReply(
            "first reply", targetId,
            "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001",
            signer.publicKeyHex(),
        )!!
        val signature = signer.sign(unsigned.messageBytes())!!
        val frame = composer.publishMessage(unsigned, signature!!)!!
        // Relay echo form: ["EVENT", subId, {event}] — the collector consumes
        // the relay frame shape, not the client publish shape.
        transport.emit(frame.replaceFirst("[\"EVENT\",", "[\"EVENT\",\"sub1\","))

        val state = withTimeout(20_000) { repository.state.first { it.comments[targetId]?.isNotEmpty() == true } }
        assertEquals(listOf("first reply"), state.comments[targetId]!!.map { it.content })
        // The reply also shows in the For You window.
        assertTrue(state.notes.any { it.content == "first reply" })
    }

    @Test
    fun followDeltaUpdatesOptimisticallyAndReturnsNewSet() = runBlocking {
        val account = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
        val followedAuthor = "e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301"
        repository.start()
        repository.setAccount(account)
        transport.emit(VALID_CONTACT_LIST_MESSAGE)
        withTimeout(20_000) { repository.state.first { it.followingResolved } }

        // Unfollow the second key: optimistic set drops it immediately.
        val updated = repository.applyFollowChange(followedAuthor, add = false)
        assertEquals(listOf("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"), updated)
        assertFalse(followedAuthor in repository.state.value.following)
        // Re-follow restores it.
        val restored = repository.applyFollowChange(followedAuthor, add = true)!!
        assertTrue(restored.contains(followedAuthor))
        // No account: nothing changes.
        repository.setAccount(null)
        assertNull(repository.applyFollowChange(followedAuthor, add = true))
    }

    @Test
    fun bookmarkDeltaUpdatesOptimistically() = runBlocking {
        val account = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
        val targetId = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
        repository.start()
        repository.setAccount(account)
        withTimeout(20_000) { repository.state.first { it.accountPubkey != null } }

        // Bookmark REQ went out for the addressable coordinate.
        withTimeout(20_000) {
            while (transport.sent.none { it.contains("bitos-bookmarks") && it.contains("30003") }) {
                kotlinx.coroutines.delay(10)
            }
        }

        // Optimistic add returns the new list for the publish.
        val updated = repository.applyBookmarkChange(targetId, add = true)
        assertEquals(listOf(targetId), updated)
        assertTrue(targetId in repository.state.value.bookmarkedIds)
        // Optimistic remove.
        val removed = repository.applyBookmarkChange(targetId, add = false)!!
        assertTrue(removed.isEmpty())
        // No account: null, local-only handled by the caller.
        repository.setAccount(null)
        assertNull(repository.applyBookmarkChange(targetId, add = true))
    }

    @Test
    fun zapReceiptsCountPerTarget() = runBlocking {
        repository.start()
        transport.emit(VALID_TEXT_NOTE_MESSAGE)
        withTimeout(20_000) { repository.state.first { it.hasLoadedAnyEvent } }

        val targetId = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
        repository.loadZaps(targetId)
        withTimeout(20_000) {
            while (transport.sent.none { it.contains("bitos-zaps") && it.contains("9735") }) {
                kotlinx.coroutines.delay(10)
            }
        }

        // Two signed zap receipts arrive; the count reflects both.
        val signer = space.bitos.core.identity.DeterministicTestSigner("0000000000000000000000000000000000000000000000000000000000000001")
        val signer2 = space.bitos.core.identity.DeterministicTestSigner("4b1aa1a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d")
        for ((authorSigner, createdAt) in listOf(signer to 1_710_000_600L, signer2 to 1_710_000_700L)) {
            val composer = space.bitos.core.publish.NoteComposer(clock = { createdAt })
            val unsigned = composeZapReceipt(targetId, authorSigner.publicKeyHex(), createdAt)
            val signature = authorSigner.sign(unsigned.messageBytes())!!
            val frame = composer.publishMessage(unsigned, signature)!!
            transport.emit(frame.replaceFirst("[\"EVENT\",", "[\"EVENT\",\"sub1\","))
        }

        val state = withTimeout(20_000) { repository.state.first { (it.zapCounts[targetId] ?: 0) >= 2 } }
        assertEquals(2, state.zapCounts[targetId])
    }

    private fun composeZapReceipt(
        targetEventId: String,
        authorPubkey: String,
        createdAt: Long,
    ): space.bitos.core.publish.UnsignedNote {
        // Kind-9735 receipt constructed directly (the composer API covers
        // client-authored kinds; receipts are authored by LNURL servers).
        return space.bitos.core.publish.UnsignedNote(
            idHex = space.bitos.core.nostr.NostrEventCodec.computeId(
                space.bitos.core.nostr.Sha256EventHasher,
                authorPubkey, createdAt, 9735,
                listOf(listOf("e", targetEventId), listOf("p", "aa".repeat(32))),
                "",
            ),
            pubkeyHex = authorPubkey,
            createdAtSeconds = createdAt,
            kind = 9735,
            tags = listOf(listOf("e", targetEventId), listOf("p", "aa".repeat(32))),
            content = "",
        )
    }

    @Test
    fun notificationRepositoryExtractsAndDedupes() = runBlocking {
        val account = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
        val notifications = space.bitos.app.data.feed.NotificationRepository(
            scope, pool,
            prefs = object : space.bitos.app.data.feed.NotificationPrefs {
                override fun readIds(): Set<String> = emptySet()
                override fun save(readIds: Set<String>) = Unit
                override fun mutedKinds(): Set<String> = emptySet()
                override fun saveMutedKinds(kinds: Set<String>) = Unit
                override fun cursorSeconds(): Long = -1L
                override fun saveCursorSeconds(seconds: Long) = Unit
            },
        )

        notifications.setAccount(account)
        withTimeout(20_000) {
            while (transport.sent.none { it.contains("bitos-notifications") && it.contains("9735") }) {
                kotlinx.coroutines.delay(10)
            }
        }

        // A signed reply mentioning the account arrives -> REPLY notification.
        val targetId = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
        val signer = space.bitos.core.identity.DeterministicTestSigner("4b1aa1a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d")
        val composer = space.bitos.core.publish.NoteComposer(clock = { 1_710_000_800 })
        val unsigned = composer.composeReply(
            "hello to you", targetId, account, signer.publicKeyHex(),
        )!!
        val signature = signer.sign(unsigned.messageBytes())!!
        val frame = composer.publishMessage(unsigned, signature)!!
        transport.emit(frame.replaceFirst("[\"EVENT\",", "[\"EVENT\",\"sub1\","))

        val state = withTimeout(20_000) { notifications.state.first { it.items.isNotEmpty() } }
        val item = state.items.single()
        assertEquals(space.bitos.core.model.NotificationKind.REPLY, item.kind)
        assertEquals("hello to you", item.summary)
        assertEquals(signer.publicKeyHex(), item.authorPubkey)
        assertTrue(state.hasAccount)
    }

    @Test
    fun searchRepositoryIssuesNip50AndFansInVerifiedResults() = runBlocking {
        val search = space.bitos.app.data.feed.SearchRepository(scope, pool)
        kotlinx.coroutines.delay(100) // collector subscribes

        search.search("hello bitos")
        withTimeout(20_000) {
            while (transport.sent.none { it.contains("bitos-search") && it.contains("hello bitos") }) {
                kotlinx.coroutines.delay(10)
            }
        }

        // A verified note matching the query arrives -> result fans in.
        transport.emit(VALID_TEXT_NOTE_MESSAGE)
        val state = withTimeout(20_000) { search.state.first { it.results.isNotEmpty() } }
        assertEquals(listOf("gm from BitOS"), state.results.map { it.content })
        assertEquals("hello bitos", state.query)

        // npub resolution triggers a targeted profile REQ.
        search.search("npub194667yy2sqh4h4vlwssg7g5smhmqx4x9hgtfdjun8e46l30kxqqselzc9y")
        withTimeout(20_000) {
            while (transport.sent.none { it.contains("bitos-search-profile") && it.contains("2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001") }) {
                kotlinx.coroutines.delay(10)
            }
        }
        val resolved = withTimeout(20_000) { search.state.first { it.resolvedNpub != null } }
        assertEquals("2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001", resolved.resolvedNpub)
    }

    @Test
    fun requestsProfilesForSeenAuthors() = runBlocking {
        repository.start()
        transport.emit(VALID_TEXT_NOTE_MESSAGE)
        withTimeout(20_000) { repository.state.first { it.hasLoadedAnyEvent } }
        // Profile REQs are debounced; await the batch request.
        withTimeout(20_000) {
            while (transport.sent.none { it.startsWith("""["REQ","bitos-profiles-""") }) {
                kotlinx.coroutines.delay(10)
            }
        }
        val request = transport.sent.first { it.startsWith("""["REQ","bitos-profiles-""") }
        assertTrue(request.contains(""""kinds":[0]"""), request)
        assertTrue(request.contains(""""limit":1"""), request)
        assertTrue(request.contains("2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"), request)
    }

    /**
     * Cold-start account bootstrap (shared `AccountBootstrap`): the one-shot
     * account heads fired by setAccount are lost when no socket is open. A
     * later connectivity episode must re-issue them; a resolved head stops
     * being re-asked even as connectivity keeps changing.
     */
    @Test
    fun coldStartReissuesLostAccountHeadsWhenRelayConnects(): Unit = runBlocking {
        val account = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
        repository.start()
        repository.setAccount(account)
        // Simulate the cold-start drop: everything the heads sent is gone.
        transport.sent.clear()

        // A relay reconnects later (connectivity grows 0 → 1).
        transport.close()
        transport.connect()
        withTimeout(20_000) {
            while (transport.sent.none { it.contains("bitos-contacts") }) kotlinx.coroutines.delay(10)
        }
        assertTrue(transport.sent.any { it.startsWith("""["REQ","bitos-profile-head-""") })
        assertTrue(transport.sent.any { it.contains("bitos-bookmarks") && it.contains("30003") })
        assertTrue(transport.sent.any { it.contains("bitos-blocks") && it.contains("10004") })

        // The contact head resolves; later episodes stop re-asking for it.
        transport.emit(VALID_CONTACT_LIST_MESSAGE)
        withTimeout(20_000) { repository.state.first { it.followingResolved } }
        val contactsSent = transport.sent.count { it.contains("bitos-contacts") }
        transport.close()
        transport.connect()
        kotlinx.coroutines.delay(300)
        assertEquals(contactsSent, transport.sent.count { it.contains("bitos-contacts") })
    }

    /**
     * The re-issue budget is bounded per connectivity episode (a constant
     * connected count never re-arms it) and a NEW relay connecting opens a
     * fresh episode.
     */
    @Test
    fun accountHeadRefetchIsBoundedPerEpisodeAndRearmsOnGrowth(): Unit = runBlocking {
        val relayB = RelayUrl.parse("wss://relay-b.test")!!
        val transportB = FakeRelayTransport(relayB)
        val twoRelayPool = RelayPool(scope, listOf(relay, relayB)) { url, _ ->
            if (url == relayB) transportB else transport
        }
        val bounded = FeedRepository(scope, twoRelayPool, hasher, cache, bootstrapPollMs = 25)
        try {
            val account = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
            bounded.start()
            // Park at exactly ONE connected relay so the connected count is
            // constant while the budget burns.
            transport.simulate(RelayConnectionState.DISCONNECTED)
            transportB.simulate(RelayConnectionState.DISCONNECTED)
            transport.simulate(RelayConnectionState.CONNECTED)
            kotlinx.coroutines.delay(100) // watcher settles at 1
            bounded.setAccount(account)
            transport.sent.clear()

            withTimeout(20_000) {
                while (transport.sent.count { it.startsWith("""["REQ","bitos-profile-head-""") } <
                    space.bitos.core.identity.AccountBootstrap.MAX_ATTEMPTS) {
                    kotlinx.coroutines.delay(10)
                }
            }
            kotlinx.coroutines.delay(200)
            val burned = transport.sent.count { it.startsWith("""["REQ","bitos-profile-head-""") }
            assertEquals(space.bitos.core.identity.AccountBootstrap.MAX_ATTEMPTS, burned)

            // A NEW relay connecting (count grows 1 → 2) opens a fresh episode.
            transportB.simulate(RelayConnectionState.CONNECTED)
            withTimeout(20_000) {
                while (transport.sent.count { it.startsWith("""["REQ","bitos-profile-head-""") } <= burned) {
                    kotlinx.coroutines.delay(10)
                }
            }
        } finally {
            bounded.stop()
        }
    }
}

private fun testNote(id: String) = FeedNote(
    id = id,
    pubkey = "author",
    content = id,
    createdAt = 0,
    kind = 1,
    replyTo = null,
    hashtags = emptyList(),
    mentions = emptyList(),
    mediaUrls = emptyList(),
    isProtocolPayload = false,
)

/** Deterministic in-memory transport double. */
private class FakeRelayTransport(private val relay: RelayUrl) : RelayTransport {
    private val mutableState = MutableStateFlow(RelayConnectionState.DISCONNECTED)
    // Replay makes emits-before-subscribe deterministic: repository.start()
// launches its collector asynchronously, so a no-replay flow would race.
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

    /** Test-only transition without the connect()/close() side effects. */
    fun simulate(state: RelayConnectionState) {
        mutableState.value = state
    }

    fun emit(message: String) {
        mutableFrames.tryEmit(RelayFrame(relay, message))
    }
}

/** In-memory cache double recording writes; recentEvents replays storage. */
private class RecordingEventCache : space.bitos.app.data.feed.EventCache {
    val stored = mutableListOf<space.bitos.core.model.NostrEvent>()

    override suspend fun upsertVerified(event: space.bitos.core.model.NostrEvent) {
        stored.removeAll { it.id == event.id }
        stored.add(event)
        stored.sortByDescending { it.createdAt }
    }

    override suspend fun recentEvents(limit: Int): List<space.bitos.core.model.NostrEvent> =
        stored.take(limit)

    override suspend fun clearAllCache() {
        stored.clear()
    }

    override suspend fun pruneToLimit(maxRows: Int) {
        while (stored.size > maxRows) stored.removeAt(stored.lastIndex)
    }
}

// Verbatim relay frames from contracts/nostr/fixtures/verification-vectors.json.
private const val VALID_TEXT_NOTE_MESSAGE =
    """["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"gm from BitOS","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]"""
private const val VALID_SECOND_KEY_MESSAGE =
    """["EVENT","sub1",{"kind":1,"created_at":1710000300,"tags":[],"content":"second author note","pubkey":"e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301","id":"c52c5fdb44ec230447a503a33f60bb729077fd8b62208456c1752d2c3dcaec1a","sig":"7d560e1a017cea1aa15271a5d0d0e3e6e8ada4f44261b770e27cadb51bf884398360b5464e39f739eb1860163b37ccedf9bf81b87e4b7026abe6d539a39bddcc"}]"""
private const val VALID_ID_WRONG_SIGNATURE_MESSAGE =
    """["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"tampered but re-identified","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"2cbc3c8affa0828e03b11f975337317f8e415397933fc87069265e17f719e95b","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]"""

private const val VALID_CONTACT_LIST_MESSAGE =
    """["EVENT","sub1",{"kind":3,"created_at":1710000400,"tags":[["p","e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301"],["p","f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"]],"content":"","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"aa63f8a27e6faa7147dcdc204e52f4d0ce16766cff232b2877187ec7c198bad3","sig":"03b0b5deb7f7013f28f12d95c4558e835a38b0f7453e2ef23d72256faa2d95fd7f2d11e2f0d11c0e83e3a8ed480b48d21b07745cb722a30986f14ec2df321425"}]"""
