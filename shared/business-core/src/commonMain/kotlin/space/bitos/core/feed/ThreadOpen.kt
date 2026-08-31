package space.bitos.core.feed

import space.bitos.core.nostr.EventRef
import space.bitos.core.nostr.EventRefs

/**
 * APP-009 thread root resolution (spec §3.9, mockup `app-10`): classify a
 * tapped/pasted reference into a fetch plan, and own the state copy so the
 * loading / invalid / not-found plates render identically on both
 * platforms. Timing (poll cadence, timeout) stays native — only the
 * deterministic decisions and copy live here.
 */
object ThreadOpen {

    /** Bare hex ids are accepted next to note1/nevent1/naddr1 (± `nostr:`). */
    fun classify(raw: String): EventRef? {
        val parsed = EventRefs.parse(raw)
        if (parsed != null) return parsed
        val hex = raw.trim().removePrefix("nostr:")
        if (hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            return EventRef.ById(id = hex, authorPubkey = null, relayHints = emptyList())
        }
        return null
    }
}

/**
 * State-plate copy (mockup `app-10` "Loading / not found" verbatim).
 * Shared like `StaticPagesContent` so neither platform drifts.
 */
object ThreadOpenCopy {
    const val TITLE = "Note"

    const val LOADING_TITLE = "Loading note from relays…"

    const val INVALID_TITLE = "That's not a valid note id"
    const val INVALID_BODY = "note1 / nevent1 / naddr1 or a 64-char hex id"

    const val NOT_FOUND_TITLE = "Your relays did not return this event"
    const val NOT_FOUND_BODY =
        "It may exist on relays you don't read. Adding the author's NIP-65 relays often helps."

    const val RETRY = "Retry"
    const val RETRY_WITH_HINTS = "Retry with hints"
    const val ADD_RELAY = "Add relay"
}
