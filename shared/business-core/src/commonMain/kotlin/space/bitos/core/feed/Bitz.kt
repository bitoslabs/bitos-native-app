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
