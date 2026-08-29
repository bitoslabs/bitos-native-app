package space.bitos.core.feed

/**
 * Bitz short-video surface rules (APP-007, spec §3.7). The deterministic
 * product behavior of the reels surface lives here once and both platforms
 * execute it: search match/merge policy, explore-grid paging bounds and
 * the share-copy builder. Timing (debounce jobs, tickers) and rendering
 * stay native per the architecture split.
 */
object BitzSearch {

    /** NIP-50 relay-search debounce (spec §3.7 search overlay). */
    const val RELAY_DEBOUNCE_MS = 400L

    /** Query bound — longer input is treated as invalid (idle state). */
    const val QUERY_MAX = 64

    /** Instant local matches cap while the relay round-trip is in flight. */
    const val LOCAL_MATCH_LIMIT = 12

    /** Final merged result cap (id-deduped local + relay). */
    const val RESULT_LIMIT = 50

    /** Searchable row projected from a feed note + resolved author name. */
    data class Entry(val id: String, val content: String, val authorName: String)

    /** Normalized query, or null when blank/oversized (the idle state). */
    fun normalizedQuery(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > QUERY_MAX) return null
        return trimmed
    }

    /** Instant local matches over the current window (bounded, newest-window order). */
    fun localMatches(query: String, entries: List<Entry>): List<Entry> {
        val q = normalizedQuery(query) ?: return emptyList()
        return entries.asSequence()
            .filter { it.id.isNotEmpty() && matches(it, q) }
            .take(LOCAL_MATCH_LIMIT)
            .toList()
    }

    /** Local-first, id-deduped, bounded merge of local + relay results. */
    fun merge(local: List<Entry>, relay: List<Entry>): List<Entry> {
        val seen = HashSet<String>()
        val out = ArrayList<Entry>(local.size + relay.size)
        for (entry in local) {
            if (entry.id.isNotEmpty() && seen.add(entry.id)) out.add(entry)
        }
        for (entry in relay) {
            if (out.size >= RESULT_LIMIT) break
            if (entry.id.isNotEmpty() && seen.add(entry.id)) out.add(entry)
        }
        return out
    }

    /** Full policy: local instant matches merged with relay results. A
     *  blank/oversized query yields nothing — stray relay echoes from an
     *  already-cancelled search must not repopulate the idle state. */
    fun results(query: String, local: List<Entry>, relay: List<Entry>): List<Entry> {
        if (normalizedQuery(query) == null) return emptyList()
        return merge(localMatches(query, local), relay)
    }

    private fun matches(entry: Entry, query: String): Boolean =
        entry.content.contains(query, ignoreCase = true) ||
            entry.authorName.contains(query, ignoreCase = true)
}

/**
 * Explore-grid paging (spec §3.7): 24 initial tiles, 18 per load-more,
 * sliced client-side from the bounded feed window (cap 200 — the window
 * bound is documented beside its owner, FeedRepository/FeedStore).
 */
object BitzExplore {
    const val INITIAL_PAGE = 24
    const val MORE_PAGE = 18

    /** Tiles visible after [loadMoreCount] load-more rounds (bounded arithmetic). */
    fun visibleCount(loadMoreCount: Int): Int =
        INITIAL_PAGE + MORE_PAGE * loadMoreCount.coerceIn(0, 100)

    fun hasMore(windowSize: Int, visibleCount: Int): Boolean = windowSize > visibleCount
}

/**
 * Legacy-compatible Bitz relay filters. Dedicated NIP-71 kinds are queried
 * separately from kind-1 media-link fallbacks so one noisy text-note filter
 * cannot consume the useful video page budget.
 */
object BitzQuery {
    const val MEDIA_PAGE_LIMIT = 16
    const val TEXT_PAGE_LIMIT = 48

    fun initialFilters(): List<String> = listOf(
        """{"kinds":[21,22],"limit":$MEDIA_PAGE_LIMIT}""",
        """{"kinds":[1],"limit":$TEXT_PAGE_LIMIT}""",
        """{"kinds":[6,0],"limit":80}""",
    )

    fun olderFilters(until: Long): List<String> = listOf(
        """{"kinds":[21,22],"limit":$MEDIA_PAGE_LIMIT,"until":$until}""",
        """{"kinds":[1],"limit":$TEXT_PAGE_LIMIT,"until":$until}""",
    )
}

/**
 * Bitz pagination walk policy (FED-004, Bitz performance study §2.2/§4.1).
 * One "load more" is a bounded backwards `until`-walk whose page budget
 * counts only FRESH media events — relays happily re-send known ids, and
 * counting those would silently shrink every page. Pure decision rules;
 * the repositories execute them (timing stays native per the split).
 *
 * Ported from the web's `loadMoreReels()` invariants:
 *  - the cursor moves monotonically backwards only,
 *  - only fresh ids count toward the page budget,
 *  - a relay that ignores `until` (no cursor advance + nothing fresh)
 *    terminates the walk instead of looping,
 *  - an empty batch means the relays are exhausted for now.
 */
object BitzTimelinePolicy {
    /** Media kinds queried deep (FED-004 widening: 20/21/22/34235/34236). */
    val MEDIA_KINDS: List<Int> = space.bitos.core.model.NostrKinds.reelMediaKinds

    /** Dedicated media kinds are ~100% renderable — query them deep. */
    const val MEDIA_INITIAL_LIMIT = 80

    /** Kind-1 is mostly text; only a shallow window carries video links. */
    const val TEXT_INITIAL_LIMIT = 120

    /** Per-batch limits of the backwards walk (web parity: 60 / 150). */
    const val MEDIA_PAGE_LIMIT = 60
    const val TEXT_PAGE_LIMIT = 150

    /** One load-more targets one full Explore reveal page of NEW media. */
    const val PAGE_FRESH_MEDIA_TARGET = 18

    /** Walk bound: 6 batches × [PAGE_MAX_WAIT_MS] is the worst-case latency. */
    const val MAX_QUERY_BATCHES = 6

    /** Hard per-batch deadline so a dead relay cannot stall the walk. */
    const val PAGE_MAX_WAIT_MS = 4_000L

    /** Prefetch when this few loaded-but-unrendered reels remain buffered. */
    const val PREFETCH_BUFFER_THRESHOLD = 6

    /** Player render-window growth per near-edge trigger. */
    const val RENDER_BATCH = 5

    /** Cursor is `oldestEventCreatedAt - 1` (`until` is exclusive). */
    fun cursor(oldestCreatedAt: Long): Long = oldestCreatedAt - 1

    fun initialFilters(): List<String> = listOf(
        """{"kinds":[${MEDIA_KINDS.joinToString(",")}],"limit":$MEDIA_INITIAL_LIMIT}""",
        """{"kinds":[1],"limit":$TEXT_INITIAL_LIMIT}""",
    )

    fun batchFilters(until: Long): List<String> = listOf(
        """{"kinds":[${MEDIA_KINDS.joinToString(",")}],"limit":$MEDIA_PAGE_LIMIT,"until":$until}""",
        """{"kinds":[1],"limit":$TEXT_PAGE_LIMIT,"until":$until}""",
    )

    /** The walk continues while the budget is unfilled and batches remain. */
    fun shouldContinue(foundFreshMedia: Int, batchesIssued: Int): Boolean =
        foundFreshMedia < PAGE_FRESH_MEDIA_TARGET && batchesIssued < MAX_QUERY_BATCHES

    /**
     * New cursor after a batch: monotonically backwards only — a relay
     * re-sending newer events must never move the walk forward.
     */
    fun advanceCursor(oldestInBatch: Long?, current: Long): Long =
        if (oldestInBatch == null) current else minOf(oldestInBatch, current)

    /**
     * A relay that returns events but neither advances the cursor nor
     * produces anything fresh is ignoring `until` — stop instead of loop.
     */
    fun relayStalled(oldestInBatch: Long?, previousCursor: Long, freshCount: Int): Boolean =
        freshCount == 0 && (oldestInBatch == null || oldestInBatch >= previousCursor)
}

/**
 * Compact rail-count labels (legacy `_formatCount` / `_compactBitSats`
 * parity): raw below 1,000, one-decimal K/M above; sats come from the
 * summed zap millisats. Locale-free by construction.
 */
object BitzFormat {
    fun count(value: Long): String = when {
        value < 0 -> "0"
        value < 1_000 -> value.toString()
        value < 1_000_000 -> oneDecimal(value / 1_000.0) + "K"
        else -> oneDecimal(value / 1_000_000.0) + "M"
    }

    /** Zap total sats from summed millisats (rounded down, min 1 when > 0). */
    fun sats(zapMillisats: Long): String? {
        if (zapMillisats <= 0) return null
        return count((zapMillisats / 1_000).coerceAtLeast(1))
    }

    private fun oneDecimal(value: Double): String {
        val tenths = (value * 10.0 + 0.5).toLong().coerceAtLeast(0)
        return "${tenths / 10}.${tenths % 10}"
    }
}

/**
 * Deterministic share copy for the Share action (note menus + video rail).
 * Excerpt + author attribution only — there is no canonical web viewer URL
 * to include, and none is invented here.
 */
object NoteShare {
    const val CONTENT_EXCERPT_MAX = 280

    fun text(content: String, authorNpub: String): String {
        val flat = content.replace('\n', ' ').trim()
        val excerpt = when {
            flat.isEmpty() -> ""
            flat.length > CONTENT_EXCERPT_MAX -> flat.take(CONTENT_EXCERPT_MAX - 1) + "…"
            else -> flat
        }
        return if (excerpt.isEmpty()) {
            "— $authorNpub · BitOS"
        } else {
            "$excerpt\n\n— $authorNpub · BitOS"
        }
    }
}
