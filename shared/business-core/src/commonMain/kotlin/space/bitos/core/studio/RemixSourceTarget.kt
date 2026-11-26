package space.bitos.core.studio

import space.bitos.core.nostr.EventRef
import space.bitos.core.nostr.EventRefs

/**
 * One pasted/scanned remix source target (plan
 * `create-hub-sound-remix-plan.md` §Remix source picker, task MEM-003a).
 *
 * The Create-hub "Remix a Bitz" entry accepts ONLY inputs that resolve to
 * one note event — a bounded `nostr:` note/nevent entity, or a supported
 * BitOS/Nostr web URL whose shape carries one. Everything else is a named
 * rejection: the picker never guesses, never fetches an ambiguous target,
 * and never builds a remix seed from an unverified id.
 *
 * Relay hints ride from the nevent TLV, capped at the remix-tag bound —
 * the lookup broadcasts them as `rels` on the by-id REQ.
 */
sealed interface RemixTarget {
    val relayHints: List<String>

    /** A note1/nevent1 (or URL embedding one): fetch by 32-byte hex id. */
    data class Event(val id: String, override val relayHints: List<String> = emptyList()) : RemixTarget
}

/** Named rejections for the paste field (copy is shared, never invented per platform). */
enum class RemixTargetRejection(val message: String) {
    EMPTY("Paste a Bitz link — nostr:note1… or nostr:nevent1…."),
    OVERSIZED("That link is too long to be a Bitz reference."),
    NOT_HTTPS("Only secure https links are supported."),
    UNSUPPORTED_HOST("That link isn't from a supported BitOS or Nostr viewer."),
    PROFILE_TARGET("That link is a profile — paste the Bitz's note or event link instead."),
    ADDRESS_TARGET("Address links aren't supported yet — paste the Bitz's note or event link."),
    MALFORMED_ENTITY("That link looks damaged — copy it again from the source app."),
    NOT_AN_EVENT("Paste a Bitz link — nostr:note1… or nostr:nevent1…."),
}

/**
 * Bounded parser for pasted/scanned remix sources (MEM-003a). Pure, no I/O,
 * no relay code: input string in, [RemixTarget] or a named
 * [RemixTargetRejection] out. Shared so both platforms accept byte-identical
 * inputs and surface byte-identical rejections.
 *
 * Accepted shapes:
 *  - `nostr:note1…` / `nostr:nevent1…` (NIP-21) and the bare entities;
 *  - BitOS web links `https://<host>/note/<nevent1|note1|hex64>` (the share
 *    format `bitz-links.ts` emits);
 *  - viewer links `https://snort.social/e/<…>`, `https://primal.net/e/<…>`,
 *    `https://iris.to/<entity>` and `https://nostrapp.link/#<entity>`.
 *
 * Bare 64-hex pastes are rejected (NOT_AN_EVENT): without a checksummed
 * carrier a typo silently retargets the fetch. Hex survives only inside an
 * allowlisted URL shape, where the host vouches for the path layout.
 */
object RemixSourceTargets {

    /** Paste-field bound (hostile input never reaches the bech32 decoder). */
    const val MAX_INPUT_LENGTH = 2_048

    /** Relay hints cap for the lookup REQ (RemixRules.MAX_RELAY_HINTS parity). */
    const val MAX_RELAY_HINTS = space.bitos.core.feed.RemixRules.MAX_RELAY_HINTS

    /** Parse [raw]; null when the input is not an acceptable target. */
    fun parse(raw: String): RemixTarget? {
        if (rejectionOf(raw) != null) return null
        val carrier = carrierOf(raw)
        val id = hexIdOf(carrier) ?: return null
        return RemixTarget.Event(
            id = id,
            relayHints = (entityRef(carrier) as? EventRef.ById)?.relayHints
                ?.take(MAX_RELAY_HINTS)
                ?: emptyList(),
        )
    }

    /**
     * The named rejection for [raw], or null when [parse] succeeds. The
     * token is stable (fixture-pinned); the message is display copy.
     */
    fun rejectionOf(raw: String): RemixTargetRejection? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return RemixTargetRejection.EMPTY
        if (trimmed.length > MAX_INPUT_LENGTH) return RemixTargetRejection.OVERSIZED

        val lowered = trimmed.lowercase()
        val entity: String = when {
            lowered.startsWith("nostr:") -> trimmed.removePrefix("nostr:").removePrefix("NOSTR:")
            lowered.startsWith("http://") -> return RemixTargetRejection.NOT_HTTPS
            lowered.startsWith("https://") -> {
                val carrier = urlCarrier(trimmed)
                return when {
                    carrier == null -> RemixTargetRejection.UNSUPPORTED_HOST
                    // A hex id rides only inside an allowlisted URL shape,
                    // where the host vouches for the path layout.
                    hex64.matches(carrier) -> null
                    else -> entityRejection(carrier)
                }
            }
            else -> trimmed
        }
        return entityRejection(entity)
    }

    private fun entityRejection(entity: String): RemixTargetRejection? = when {
        entity.startsWith("npub1") || entity.startsWith("nprofile1") ->
            RemixTargetRejection.PROFILE_TARGET
        entity.startsWith("naddr1") -> RemixTargetRejection.ADDRESS_TARGET
        entity.startsWith("note1") || entity.startsWith("nevent1") ->
            if (entityRef(entity) is EventRef.ById) null else RemixTargetRejection.MALFORMED_ENTITY
        else -> RemixTargetRejection.NOT_AN_EVENT
    }

    private fun entityRef(value: String): EventRef? =
        runCatching { EventRefs.parse(value) }.getOrNull()

    /** The 32-byte hex id of a carrier entity or hex-shaped URL segment. */
    private fun hexIdOf(carrier: String): String? = when {
        hex64.matches(carrier) -> carrier.lowercase()
        else -> (entityRef(carrier) as? EventRef.ById)?.id
    }

    // ── Supported URL shapes ───────────────────────────────────────────
    //
    // Every rule extracts the candidate carrier (bech32 entity or hex id)
    // from a bounded path/fragment; the carrier still passes the same
    // entity decode, so a hostile URL can only produce a target the bare
    // paste path would also accept (or a named rejection).

    private val hex64 = Regex("^[0-9a-fA-F]{64}$")

    /** Viewer hosts whose `/e/<carrier>` path carries a note reference. */
    private val viewerHosts = setOf("snort.social", "www.snort.social", "primal.net", "www.primal.net")

    /** Split `https://host/path?query#fragment` without a platform URI type. */
    private data class ParsedUrl(val host: String, val pathSegments: List<String>, val fragment: String?)

    private fun parseUrl(url: String): ParsedUrl? {
        if (!url.startsWith("https://", ignoreCase = true)) return null
        val rest = url.substring("https://".length)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringBefore(':').lowercase()
        if (host.isEmpty() || host.any { it !in 'a'..'z' && it !in '0'..'9' && it !in ".-" }) return null
        val afterAuthority = rest.drop(authority.length)
        val pathAndMore = afterAuthority.substringBefore('#')
        val path = pathAndMore.substringBefore('?')
        val fragment = rest.substringAfter('#', missingDelimiterValue = "").takeIf(String::isNotEmpty)
        val segments = path.split('/').filter(String::isNotEmpty).take(MAX_URL_SEGMENTS)
        return ParsedUrl(host = host, pathSegments = segments, fragment = fragment)
    }

    /** The note carrier inside a supported URL, or null (unsupported shape). */
    private fun urlCarrier(url: String): String? {
        val parsed = parseUrl(url) ?: return null
        return when {
            // nostrapp.link/#nevent1… — the fragment IS the entity (the
            // optional nostr: prefix is tolerated).
            parsed.host == "nostrapp.link" || parsed.host == "www.nostrapp.link" ->
                parsed.fragment?.removePrefix("nostr:")?.takeIf(::isCarrier)

            // snort/primal note permalinks: /e/<carrier>.
            parsed.host in viewerHosts && parsed.pathSegments.firstOrNull() == "e" ->
                parsed.pathSegments.getOrNull(1)?.takeIf(::isCarrier)

            // iris.to/<entity> — the path root IS the entity.
            parsed.host == "iris.to" || parsed.host == "www.iris.to" ->
                parsed.pathSegments.firstOrNull()?.takeIf(::isCarrier)

            // BitOS web share links: <origin>/note/<carrier> on any host
            // (deployments vary; the strict path shape is the contract).
            parsed.pathSegments.firstOrNull() == "note" ->
                parsed.pathSegments.getOrNull(1)?.takeIf(::isCarrier)

            else -> null
        }
    }

    /** The entity/hex string this input carries, for [hexIdOf]. */
    private fun carrierOf(raw: String): String {
        val trimmed = raw.trim()
        val lowered = trimmed.lowercase()
        return when {
            lowered.startsWith("nostr:") -> trimmed.removePrefix("nostr:").removePrefix("NOSTR:")
            lowered.startsWith("https://") -> urlCarrier(trimmed) ?: ""
            else -> trimmed
        }
    }

    private fun isCarrier(candidate: String): Boolean =
        candidate.startsWith("note1") || candidate.startsWith("nevent1") || hex64.matches(candidate)

    private const val MAX_URL_SEGMENTS = 8
}
