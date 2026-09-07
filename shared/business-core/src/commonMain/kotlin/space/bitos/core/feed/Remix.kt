package space.bitos.core.feed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

/**
 * NIP-01 remix attribution rules (APP-007 rail parity with the legacy web
 * `src/lib/meme/remix.ts`). The native V1 publishes the same wire format
 * the web studio publishes — `["remix", <event-id>, <relay-hint>…]` plus a
 * `p`-tag attribution for the source author and a human-readable
 * `["attribution", "remix of …"]` tag — and reads license gating the same
 * advisory way: restrictive licenses ask for confirmation, they never hide
 * the action. The meme-tag layout payload itself belongs to the studio
 * editor (APP-019) and is deliberately not composed here.
 */
object RemixRules {

    /** Relay hints ride inside the remix tag, capped like the web studio. */
    const val MAX_RELAY_HINTS = 3

    /** Hostile-input bound for one relay hint URL. */
    const val MAX_RELAY_URL_LENGTH = 2048

    /** Human credit bound (legacy `slice(0, 140)`). */
    const val ATTRIBUTION_MAX = 140

    /** Legacy license codes (`license` tag); unknown codes stay remixable. */
    val LICENSES = setOf(
        "CC0-1.0",
        "CC-BY-4.0",
        "CC-BY-NC-4.0",
        "bitz/source-permission",
        "bitz/all-reserved",
    )

    /** Licenses that require asking the creator (advisory, never hidden). */
    val ASK_REQUIRED_LICENSES = setOf("bitz/source-permission", "bitz/all-reserved")

    /** Parsed remix source of one note (wire: `["remix", id, relays…]`
     *  or the graph form `["bitz:edge", "remix", "event:<id>", "1"]`). */
    data class Source(val eventId: String, val pubkey: String?, val relays: List<String>)

    /** First remix source declared in [tags], or null for original work. */
    fun sourceOf(tags: List<List<String>>): Source? {
        var eventId: String? = null
        var relays: List<String> = emptyList()
        for (tag in tags) {
            when {
                tag.firstOrNull() == "remix" && tag.size >= 2 -> {
                    eventId = tag[1].takeIf { it.length in 1..512 }
                    relays = tag.drop(2).take(MAX_RELAY_HINTS)
                    break
                }
                tag.firstOrNull() == "bitz:edge" && tag.size >= 4 && tag[1] == "remix" -> {
                    val ref = tag[2]
                    if (ref.startsWith("event:") && ref.length > "event:".length) {
                        eventId = ref.substringAfter("event:").takeIf { it.length in 1..512 }
                        relays = emptyList()
                        break
                    }
                }
            }
        }
        val id = eventId ?: return null
        val pubkey = tags.firstOrNull { it.firstOrNull() == "p" && it.size >= 2 }
            ?.get(1)?.takeIf { it.length in 1..128 }
        return Source(eventId = id, pubkey = pubkey, relays = relays)
    }

    /**
     * Relay hints for a remix publish (web `remixReel` parity): the source
     * event's own remix-tag hints first, then the composer's write relays,
     * deduped (insertion order) and capped at [MAX_RELAY_HINTS]. Blank and
     * over-long entries drop — relay data is untrusted.
     */
    fun relayHints(sourceRelays: List<String>, writeRelays: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (relay in sourceRelays + writeRelays) {
            val trimmed = relay.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_RELAY_URL_LENGTH) continue
            seen.add(trimmed)
        }
        return seen.toList().take(MAX_RELAY_HINTS)
    }

    /** Wire tags for a remix publish: remix marker + p attribution. */
    fun tagsFor(eventId: String, pubkey: String?, relays: List<String> = emptyList()): List<List<String>> {
        val remix = buildList {
            add("remix")
            add(eventId)
            addAll(relays.take(MAX_RELAY_HINTS))
        }
        return buildList {
            add(remix)
            if (pubkey != null) add(listOf("p", pubkey))
        }
    }

    /** Human credit tag: `["attribution", "remix of <label>"]`, bounded. */
    fun attributionTag(label: String): List<String> {
        val credit = "remix of $label".take(ATTRIBUTION_MAX)
        return listOf("attribution", credit)
    }

    /** `license` tag value, or null when the creator set none (permissive). */
    fun licenseOf(tags: List<List<String>>): String? =
        tags.firstOrNull { it.firstOrNull() == "license" && it.size >= 2 }
            ?.get(1)?.takeIf { it.isNotBlank() }

    /** Advisory gate (web `canRemix` parity): false → ask, never hide. */
    fun requiresAsk(license: String?): Boolean = license in ASK_REQUIRED_LICENSES

    /**
     * Merge seed tags (remix/p/attribution) with the composer's derived
     * tags, deduping by (name, first parameter) so a mention-derived `p`
     * tag never duplicates the remix attribution. Deterministic, base-first.
     */
    fun mergeTags(base: List<List<String>>, derived: List<List<String>>): List<List<String>> {
        val seen = HashSet<Pair<String, String>>()
        val out = ArrayList<List<String>>(base.size + derived.size)
        for (tag in base + derived) {
            val name = tag.firstOrNull() ?: continue
            val key = name to (tag.getOrNull(1) ?: "")
            if (seen.add(key)) out.add(tag)
        }
        return out
    }

    /** JSON seam for iOS: merge two tag arrays (`[[name, …], …]`). */
    fun mergeTagsJson(baseJson: String, derivedJson: String): String = try {
        val base = Json.parseToJsonElement(baseJson).jsonArray.map { el ->
            el.jsonArray.map { it.toString().trim('"') }
        }
        val derived = Json.parseToJsonElement(derivedJson).jsonArray.map { el ->
            el.jsonArray.map { it.toString().trim('"') }
        }
        val merged = mergeTags(base, derived).joinToString(prefix = "[", postfix = "]") { tag ->
            tag.joinToString(prefix = "[", separator = ",", postfix = "]") { value ->
                "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            }
        }
        merged
    } catch (_: Exception) {
        derivedJson
    }
}

/**
 * Remix ancestry walk (APP-007 Chain sheet, legacy web `remixChainOf`
 * parity): step from one source to the next through `remix` tags, fetching
 * each ancestor's tags through the caller-supplied (suspendable) lookup.
 * A missing parent is the natural end (pruned history degrades gently);
 * a repeated id refuses the walk; a non-hex id ends it (hostile data must
 * never reach a relay REQ).
 */
object RemixChain {
    /** Legacy parity: chains longer than this surface truncated. */
    const val MAX_DEPTH = 32

    /** One ancestor: depth 0 = direct source of the tapped note. */
    data class Step(val eventId: String, val pubkey: String?, val depth: Int)

    sealed interface Outcome {
        /** Steps traced; [truncated] marks the 32-cap. */
        data class Completed(val steps: List<Step>, val truncated: Boolean) : Outcome
        /** Repeated id in the ancestry — the lineage loops. */
        data object Cycle : Outcome
    }

    private val hex64 = Regex("^[0-9a-f]{64}$")

    /**
     * Walks the ancestry starting at [source] of the note [rootId].
     * [lookup] returns the tags of an ancestor id (null = unresolvable).
     */
    suspend fun walk(
        rootId: String,
        source: RemixRules.Source?,
        lookup: suspend (String) -> List<List<String>>?,
    ): Outcome {
        val steps = ArrayList<Step>()
        val visited = HashSet<String>()
        visited.add(rootId)
        var current = source
        var truncated = false
        while (current != null) {
            val id = current.eventId
            if (!hex64.matches(id)) return Outcome.Completed(steps, truncated)
            if (!visited.add(id)) return Outcome.Cycle
            steps.add(Step(eventId = id, pubkey = current.pubkey, depth = steps.size))
            if (steps.size >= MAX_DEPTH) {
                truncated = true
                break
            }
            val tags = lookup(id) ?: return Outcome.Completed(steps, truncated = false)
            current = RemixRules.sourceOf(tags)
        }
        return Outcome.Completed(steps, truncated)
    }
}
