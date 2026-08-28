package space.bitos.core.model

/**
 * APP-008 poll wire contract (legacy Flutter `PollComposer` / web
 * `feed.postPoll` parity): a poll is a kind-1 text note whose content is
 * the question and whose options are `["poll_option", <index>, <label>]`
 * tags. Bounds: question ≤ [MAX_QUESTION], 2–6 options of ≤ [MAX_OPTION]
 * chars at compose time; parsing is tolerant but display-bounded
 * (hostile events render at most [DISPLAY_MAX] options).
 */
data class PollOption(val index: Int, val label: String)

data class Poll(val question: String, val options: List<PollOption>) {
    val totalOptions: Int get() = options.size
}

object PollContract {

    const val TAG = "poll_option"

    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 6
    const val MAX_QUESTION = 280
    const val MAX_OPTION = 80

    /** Hostile-event display bound (compose caps at [MAX_OPTIONS]). */
    const val DISPLAY_MAX = 16

    /** True when the kind-1 event carries ≥2 usable poll_option tags. */
    fun isPoll(event: NostrEvent): Boolean = poll(event) != null

    /**
     * Parses the poll projection: options deduped by index (first wins),
     * sorted by index, labels trimmed, display-bounded. Null when fewer
     * than two usable options or the kind is not a short text note.
     */
    fun poll(event: NostrEvent): Poll? {
        if (event.kind != NostrKinds.SHORT_TEXT_NOTE) return null
        val byIndex = LinkedHashMap<Int, String>()
        for (tag in event.tags) {
            if (tag.firstOrNull() != TAG) continue
            val index = tag.getOrNull(1)?.toIntOrNull() ?: continue
            if (index < 0 || index > 255) continue
            val label = tag.getOrNull(2)?.trim().orEmpty()
            if (label.isEmpty()) continue
            if (label.length > MAX_OPTION * 2) continue // hostile bound
            if (!byIndex.containsKey(index)) byIndex[index] = label
        }
        if (byIndex.size < MIN_OPTIONS) return null
        val options = byIndex.entries.sortedBy { it.key }
            .take(DISPLAY_MAX)
            .map { PollOption(it.key, it.value) }
        if (options.size < MIN_OPTIONS) return null
        val question = event.content.trim().take(MAX_QUESTION * 2)
        return Poll(question, options)
    }

    /** Validated poll tags for a composed note (index 0-based, in order). */
    fun pollTags(question: String, options: List<String>): List<List<String>>? {
        val clean = options.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.size < MIN_OPTIONS || clean.size > MAX_OPTIONS) return null
        if (clean.any { it.length > MAX_OPTION }) return null
        val trimmedQuestion = question.trim()
        if (trimmedQuestion.isEmpty() || trimmedQuestion.length > MAX_QUESTION) return null
        return clean.mapIndexed { index, label -> listOf(TAG, index.toString(), label) }
    }
}
