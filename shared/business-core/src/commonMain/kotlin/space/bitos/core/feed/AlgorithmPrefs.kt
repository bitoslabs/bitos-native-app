package space.bitos.core.feed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Surfaces the client-side ranking system serves (origin-app parity).
 */
enum class AlgorithmSurface(val wire: String) {
    FEED("feed"), REELS("reels"), DISCOVER("discover"),
}

/**
 * Ranking signals (origin-app catalog). TOPICS and WOT stay configurable —
 * presets carry their weights — but contribute 0 until their data feeds
 * (topic profile, second-hop set) land; adapters mark those rows pending.
 */
enum class AlgorithmSignal(val wire: String) {
    RECENCY("recency"), ENGAGEMENT("engagement"), ZAPS("zaps"),
    AFFINITY("affinity"), TOPICS("topics"), WOT("wot"),
}

/** One signal's user configuration: enabled + weight on a 0..1 slider. */
data class SignalSetting(val enabled: Boolean, val weight: Double)

/** One surface's configuration: master switch (off = chronological) + the
 *  author-clustering diversity pass (post-scoring requeue, never drop). */
data class SurfaceSetting(
    val enabled: Boolean,
    val diversityEnabled: Boolean = true,
    val signals: Map<AlgorithmSignal, SignalSetting> = emptyMap(),
) {
    constructor(enabled: Boolean, signals: Map<AlgorithmSignal, SignalSetting>) :
        this(enabled, diversityEnabled = true, signals = signals)
}

/** Immutable algorithm snapshot — the whole persisted preference state. */
data class AlgorithmSnapshot(
    /** Recency half-life hours (origin freshness control). */
    val freshnessHours: Int = AlgorithmContract.FRESHNESS_DEFAULT_HOURS,
    val surfaces: Map<AlgorithmSurface, SurfaceSetting> = AlgorithmSurface.entries.associateWith {
        AlgorithmContract.preset(it, AlgorithmPresetId.BALANCED)
    },
)

/** One-tap presets (origin parity; CUSTOM = anything user-tuned). */
enum class AlgorithmPresetId { LATEST, BALANCED, TRENDING, TRUSTED, CUSTOM }

/**
 * Versioned algorithm-preferences contract (origin `algorithm-plan.md`
 * parity): presets per surface, freshness steps, weight bounds and a
 * compact lenient JSON wire for device persistence. Corrupt or oversized
 * stores decode to defaults — a broken store must never crash settings.
 */
object AlgorithmContract {
    const val SCHEMA_VERSION = 2
    const val MAX_WIRE_LENGTH = 2_048

    /** Freshness steps: Live 1h · Balanced 6h · Relaxed 24h · Chill 3d. */
    val FRESHNESS_STEPS_HOURS = listOf(1, 6, 24, 72)
    const val FRESHNESS_DEFAULT_HOURS = 6

    const val WEIGHT_STEP = 0.05

    /** Preset weights per surface (origin defaults; unused signals sit at 0). */
    fun preset(surface: AlgorithmSurface, preset: AlgorithmPresetId): SurfaceSetting = when (preset) {
        AlgorithmPresetId.LATEST -> SurfaceSetting(
            enabled = true,
            signals = weights(surface, recency = 1.0),
        )
        AlgorithmPresetId.BALANCED -> when (surface) {
            AlgorithmSurface.FEED -> SurfaceSetting(true, weights(surface, recency = 0.30, affinity = 0.25, topics = 0.15, engagement = 0.15, wot = 0.10, zaps = 0.05))
            AlgorithmSurface.REELS -> SurfaceSetting(true, weights(surface, engagement = 0.45, zaps = 0.30, recency = 0.15, affinity = 0.10))
            AlgorithmSurface.DISCOVER -> SurfaceSetting(true, weights(surface, engagement = 0.40, wot = 0.30, zaps = 0.20, recency = 0.10))
        }
        AlgorithmPresetId.TRENDING -> when (surface) {
            AlgorithmSurface.FEED -> SurfaceSetting(true, weights(surface, engagement = 0.35, zaps = 0.30, recency = 0.25, affinity = 0.10))
            AlgorithmSurface.REELS -> SurfaceSetting(true, weights(surface, engagement = 0.45, zaps = 0.40, recency = 0.15))
            AlgorithmSurface.DISCOVER -> SurfaceSetting(true, weights(surface, engagement = 0.45, zaps = 0.35, recency = 0.20))
        }
        AlgorithmPresetId.TRUSTED -> when (surface) {
            AlgorithmSurface.FEED -> SurfaceSetting(true, weights(surface, wot = 0.35, affinity = 0.30, recency = 0.25, topics = 0.10))
            AlgorithmSurface.REELS -> SurfaceSetting(true, weights(surface, affinity = 0.35, wot = 0.30, engagement = 0.20, recency = 0.15))
            AlgorithmSurface.DISCOVER -> SurfaceSetting(true, weights(surface, wot = 0.45, affinity = 0.25, engagement = 0.20, recency = 0.10))
        }
        AlgorithmPresetId.CUSTOM -> SurfaceSetting(
            enabled = false,
            signals = weights(surface, recency = 1.0),
        )
    }

    /** Preset the surface config matches (CUSTOM once the user strays). */
    fun detectPreset(surface: AlgorithmSurface, setting: SurfaceSetting): AlgorithmPresetId {
        for (id in listOf(AlgorithmPresetId.LATEST, AlgorithmPresetId.BALANCED, AlgorithmPresetId.TRENDING, AlgorithmPresetId.TRUSTED)) {
            if (preset(surface, id) == setting) return id
        }
        return AlgorithmPresetId.CUSTOM
    }

    /** Canonicalizes weights (0..1 in [WEIGHT_STEP] increments, FP-stable) and fills gaps. */
    fun normalize(setting: SurfaceSetting): SurfaceSetting = SurfaceSetting(
        enabled = setting.enabled,
        diversityEnabled = setting.diversityEnabled,
        signals = AlgorithmSignal.entries.associateWith { signal ->
            val raw = setting.signals[signal] ?: SignalSetting(enabled = false, weight = 0.0)
            // Integer-cent step math keeps round-trips stable (double
            // division truncated 0.35 → 0.30 → 0.25 across encode/decode).
            val cents = kotlin.math.round(raw.weight.coerceIn(0.0, 1.0) * 100.0).toInt()
            val steppedCents = cents / (WEIGHT_STEP * 100).toInt() * (WEIGHT_STEP * 100).toInt()
            val stepped = steppedCents / 100.0
            SignalSetting(enabled = raw.enabled && stepped > 0.0, weight = stepped)
        },
    )

    fun normalize(snapshot: AlgorithmSnapshot): AlgorithmSnapshot = AlgorithmSnapshot(
        freshnessHours = if (snapshot.freshnessHours in FRESHNESS_STEPS_HOURS) snapshot.freshnessHours else FRESHNESS_DEFAULT_HOURS,
        surfaces = AlgorithmSurface.entries.associateWith { surface ->
            normalize(snapshot.surfaces[surface] ?: preset(surface, AlgorithmPresetId.BALANCED))
        },
    )

    // ── Wire codec (compact, lenient) ───────────────────────────────

    fun encode(snapshot: AlgorithmSnapshot): String {
        val surfaces = buildJsonObject {
            AlgorithmSurface.entries.forEach { surface ->
                val setting = normalize(snapshot).surfaces[surface]!!
                put(surface.wire, buildJsonObject {
                    put("e", if (setting.enabled) 1 else 0)
                    put("d", if (setting.diversityEnabled) 1 else 0)
                    put("g", buildJsonObject {
                        setting.signals.forEach { (signal, s) ->
                            put(signal.wire, buildJsonObject {
                                put("e", if (s.enabled) 1 else 0)
                                put("w", s.weight)
                            })
                        }
                    })
                })
            }
        }
        return buildJsonObject {
            put("v", SCHEMA_VERSION)
            put("f", normalize(snapshot).freshnessHours)
            put("s", surfaces)
        }.toString()
    }

    fun decode(json: String): AlgorithmSnapshot {
        if (json.length > MAX_WIRE_LENGTH) return AlgorithmSnapshot()
        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            val freshness = root["f"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: FRESHNESS_DEFAULT_HOURS
            val surfaces = AlgorithmSurface.entries.mapNotNull { surface ->
                val obj = root["s"]?.jsonObject?.get(surface.wire)?.jsonObject ?: return@mapNotNull null
                val diversity = obj["d"]?.jsonPrimitive?.content != "0"
                val signals = AlgorithmSignal.entries.mapNotNull { signal ->
                    val s = obj["g"]?.jsonObject?.get(signal.wire)?.jsonObject ?: return@mapNotNull null
                    val enabled = s["e"]?.jsonPrimitive?.content == "1"
                    val weight = s["w"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    signal to SignalSetting(enabled, weight)
                }.toMap()
                surface to SurfaceSetting(obj["e"]?.jsonPrimitive?.content == "1", diversity, signals)
            }.toMap()
            normalize(AlgorithmSnapshot(freshness, surfaces))
        } catch (_: Exception) {
            AlgorithmSnapshot()
        }
    }

    private fun weights(
        @Suppress("UNUSED_PARAMETER") surface: AlgorithmSurface,
        recency: Double = 0.0,
        engagement: Double = 0.0,
        zaps: Double = 0.0,
        affinity: Double = 0.0,
        topics: Double = 0.0,
        wot: Double = 0.0,
    ): Map<AlgorithmSignal, SignalSetting> =
        mapOf(
            AlgorithmSignal.RECENCY to recency,
            AlgorithmSignal.ENGAGEMENT to engagement,
            AlgorithmSignal.ZAPS to zaps,
            AlgorithmSignal.AFFINITY to affinity,
            AlgorithmSignal.TOPICS to topics,
            AlgorithmSignal.WOT to wot,
        ).mapValues { (_, w) -> SignalSetting(enabled = w > 0.0, weight = round2(w)) }

    /** Two-decimal canonical form keeps step math FP-clean (0.3, not 0.30000…4). */
    private fun round2(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0
}
