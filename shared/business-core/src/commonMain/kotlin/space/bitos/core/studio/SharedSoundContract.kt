package space.bitos.core.studio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Shared sound events (plan MST-047 / §3.5): kind-30078 addressable
 * events with `d = "com.bitos.bitz:sound:<id>"` whose tags carry
 * `url / x(sha256) / license / attribution / t… / image / p` and whose
 * content is `{label, durationSec?, mime? (default audio/webm),
 * description?}`. This is the LICENSED LIBRARY rail ("online system") —
 * distinct from the derived trending rank (APP-021 bootstrap), which
 * reads `sound` tags off published memes.
 *
 * Ingest is license-gated (plan: only CC0-1.0 / CC-BY-4.0 / CC-BY-NC-4.0
 * — stricter than remix's advisory gate: non-open licenses simply never
 * reach the rail) and sha256-expected: the tag hash must be canonical
 * 64-hex and the DOWNLOADED BYTES must verify against it (plus the
 * ≤ 15 s / ≤ 8 MB local-library bounds) before a sound attaches —
 * [ingestCheck] is the one pure seam for that download-side gate.
 *
 * Every read is hostile-tolerant (MEM-004): junk fields clamp, foreign
 * shapes → null, and a sound without a usable url / canonical sha /
 * ingestable license is rejected outright.
 */
object SharedSoundContract {

    /** NIP-78 namespaced app data (NostrKinds.APP_DATA). */
    const val KIND = space.bitos.core.model.NostrKinds.APP_DATA

    const val D_TAG_PREFIX = "com.bitos.bitz:sound:"
    const val SCHEMA = "com.bitos.bitz.sound"
    const val VERSION = 1

    /** Content bounds (plan §3.5). */
    const val MAX_LABEL = 40
    const val MAX_DESCRIPTION = 500
    const val MAX_MIME = 64
    const val DEFAULT_MIME = "audio/webm"
    const val MAX_DURATION_SEC = 15
    const val MAX_DURATION_MS = MAX_DURATION_SEC * 1_000L

    /** Tag bounds (plan §3.5). */
    const val MAX_URL_LENGTH = 2_048
    const val MAX_ATTRIBUTION = space.bitos.core.feed.RemixRules.ATTRIBUTION_MAX
    const val MAX_TOPICS = 10
    const val MAX_TOPIC_LENGTH = 40
    const val MAX_IMAGE_URL_LENGTH = 2_048
    const val MAX_AUTHOR_LENGTH = MemeSoundRules.MAX_AUTHOR_LENGTH
    const val MAX_ID_LENGTH = 48
    const val MAX_LICENSE_LENGTH = 64

    /** Local-library caps (plan §3.5: ≤ 30 sounds, ≤ 8 MB, ≤ 15 s). */
    const val MAX_LOCAL_SOUNDS = 30
    const val MAX_BYTES = 8L * 1_024 * 1_024

    /** Rail cap for relay-fetched shared sounds (template parity). */
    const val MAX_SHARED_ROWS = MemeTemplateContract.MAX_SHARED_ROWS

    /** Only these licenses ingest (plan: CC0 / CC-BY / CC-BY-NC). */
    val INGESTABLE_LICENSES = setOf(
        "CC0-1.0",
        "CC-BY-4.0",
        "CC-BY-NC-4.0",
    )

    /** 64 lowercase hex — the canonical sound-bytes content hash. */
    private val shaPattern = Regex("^[0-9a-f]{64}$")

    data class SharedSound(
        val id: String,
        val label: String,
        /** Blossom (or any https) URL of the audio bytes. */
        val url: String,
        /** Expected canonical content hash of those bytes (`x` tag). */
        val sha256: String,
        val license: String,
        /** Credit line from the event ("sound of …" parity, ≤ 140). */
        val attribution: String,
        /** Declared duration; 0 = undeclared (decode decides, ≤ 15 s). */
        val durationMs: Long,
        val mime: String,
        val description: String,
        val topics: List<String>,
        /** Optional cover art URL (rail thumbnail). */
        val imageUrl: String,
        /** Author pubkey (`p` tag) — provenance for the attach path. */
        val authorPubkey: String,
    )

    /** True for `d` tags addressing a BitOS shared-sound event. */
    fun isSoundDTag(d: String): Boolean = d.startsWith(D_TAG_PREFIX)

    /**
     * Parses one event (tags + content). Null when the shape is foreign
     * (not our d-tag / not JSON / no usable url / non-canonical sha /
     * non-ingestable license / declared duration over the 15 s bound).
     * Everything else clamps (MEM-004 hostile tolerance).
     */
    fun parse(tags: List<List<String>>, content: String): SharedSound? {
        val dTag = tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)
            ?.takeIf { isSoundDTag(it) } ?: return null
        val id = dTag.removePrefix(D_TAG_PREFIX).take(MAX_ID_LENGTH).ifBlank { return null }
        val root = runCatching { Json.parseToJsonElement(content).jsonObject }.getOrNull() ?: return null
        val schema = (root["schema"] as? JsonPrimitive)?.content
        if (schema != null && schema != SCHEMA) return null
        val version = (root["version"] as? JsonPrimitive)?.content?.toIntOrNull() ?: VERSION
        if (version < 1 || version > VERSION) return null

        val url = tags.firstOrNull { it.firstOrNull() == "url" }?.getOrNull(1)
            ?.trim()?.take(MAX_URL_LENGTH)?.takeIf(String::isNotBlank) ?: return null
        val sha256 = tags.firstOrNull { it.firstOrNull() == "x" }?.getOrNull(1)
            ?.lowercase()?.take(MemeSoundRules.MAX_SHA_LENGTH) ?: return null
        if (!shaPattern.matches(sha256)) return null
        val license = tags.firstOrNull { it.firstOrNull() == "license" }?.getOrNull(1)
            ?.take(MAX_LICENSE_LENGTH) ?: return null
        if (license !in INGESTABLE_LICENSES) return null

        val declaredSec = (root["durationSec"] as? JsonPrimitive)?.content?.toDoubleOrNull()
        if (declaredSec != null && (declaredSec < 0 || declaredSec > MAX_DURATION_SEC || declaredSec.isNaN())) return null
        val durationMs = ((declaredSec ?: 0.0) * 1_000L).toLong()

        return SharedSound(
            id = id,
            label = ((root["label"] as? JsonPrimitive)?.content ?: id)
                .take(MAX_LABEL).ifBlank { id },
            url = url,
            sha256 = sha256,
            license = license,
            attribution = tags.firstOrNull { it.firstOrNull() == "attribution" }?.getOrNull(1)
                ?.take(MAX_ATTRIBUTION)?.takeIf(String::isNotBlank) ?: "",
            durationMs = durationMs,
            mime = ((root["mime"] as? JsonPrimitive)?.content ?: DEFAULT_MIME)
                .take(MAX_MIME).ifBlank { DEFAULT_MIME },
            description = (root["description"] as? JsonPrimitive)?.content.orEmpty()
                .take(MAX_DESCRIPTION),
            topics = tags.filter { it.firstOrNull() == "t" }
                .mapNotNull { it.getOrNull(1)?.take(MAX_TOPIC_LENGTH)?.takeIf(String::isNotBlank) }
                .distinct().take(MAX_TOPICS),
            imageUrl = tags.firstOrNull { it.firstOrNull() == "image" }?.getOrNull(1)
                ?.take(MAX_IMAGE_URL_LENGTH).takeIf { !it.isNullOrBlank() } ?: "",
            authorPubkey = tags.firstOrNull { it.firstOrNull() == "p" && it.size >= 2 }
                ?.get(1)?.take(MAX_AUTHOR_LENGTH).takeIf { !it.isNullOrBlank() } ?: "",
        )
    }

    /**
     * Download-side ingest gate (pure seam for the native "Use sound"
     * handoff): the DECODED duration must be positive and ≤ 15 s and the
     * byte count ≤ 8 MB — the declared event duration is advisory, the
     * decode is the truth.
     */
    fun ingestCheck(decodedDurationMs: Long, byteCount: Long): Boolean =
        decodedDurationMs in 1..MAX_DURATION_MS && byteCount in 1..MAX_BYTES
}
