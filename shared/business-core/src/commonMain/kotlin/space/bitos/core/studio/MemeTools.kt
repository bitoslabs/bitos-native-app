package space.bitos.core.studio

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Meme Studio shell contract (plan `meme-studio-ux-redesign-plan.md`,
 * MSU-001): the single cross-platform source for **which tools exist, what
 * they are called, their icon key, and which mode/selection they apply to**.
 *
 * Why this lives in the shared core: before this contract each platform
 * hand-rolled its own tool lists (Android `PerModeBar` + `QuickToolsRow`,
 * iOS `perModeBar` + `quickTools` + `SuiteDockView`). That drift produced
 * the shipped usability bugs this plan fixes — one panel reachable from
 * three chips, one glyph meaning three things, and a second editor reached
 * through a chip labelled "Timeline". Rendering from one catalogue makes
 * those states unrepresentable and turns "add a tool" into a one-place
 * change.
 *
 * Pure and deterministic: no UI, no platform types. Both platforms decode
 * [catalogJson] and render their native control for each row.
 *
 * Shell tiers
 * ----------
 *  • **Primary** ([primaryFor]) — the always-visible bar. At most
 *    [MAX_PRIMARY_TOOLS] tiles. The same vocabulary and the same order in
 *    every mode; a mode only omits tools it genuinely cannot use (GIF has
 *    no audio track, so no Sound).
 *  • **Advanced** ([advancedFor]) — the overflow behind the More tile
 *    (workspace entry, canvas, layers, draw, per-clip tools, batching).
 *  • **Selection** ([selectionFor]) — contextual actions for what is
 *    selected; shown only while something is selected.
 *
 * Invariants enforced by `MemeToolsTest`:
 *  1. A tool id appears in exactly one tier (never primary + advanced).
 *  2. No mode repeats a tool id in its primary list.
 *  3. Media · Text · Sticker · Look are present in every mode.
 *  4. Primary order is a stable prefix of [PRIMARY_ORDER].
 *  5. No filtered/adjust "duplicate" tool id exists — grade, manual adjust
 *     and motion are ONE tool ([ToolId.LOOK]) with three sections.
 */
object MemeTools {

    /** Shell placement of a tool. One id belongs to exactly one tier. */
    enum class Tier { PRIMARY, ADVANCED, SELECTION }

    /**
     * Stable tool ids. These are the contract both platforms switch on —
     * never rename an existing id (add a new one and deprecate instead).
     */
    enum class ToolId(val label: String, val iconKey: String) {
        // ── Primary (always-visible bar) ────────────────────────────────
        /** Pick / switch / add media (opens the media sheet). */
        MEDIA("Media", "media"),
        /** Add or edit text (enters on-canvas compose). */
        TEXT("Text", "text"),
        /** Sticker sheet. */
        STICKER("Sticker", "sticker"),
        /** Soundtrack + synth SFX. VIDEO only (GIF is silent). */
        SOUND("Sound", "sound"),
        /** Grade · Adjust · Motion — ONE sheet with three sections. */
        LOOK("Look", "look"),

        // ── Advanced (More overflow) ────────────────────────────────────
        /** Classic top/bottom meme captions (the "Meme generator" sheet). */
        CAPTIONS("Captions", "captions"),
        /** Canvas ratio + background. */
        CANVAS("Canvas", "canvas"),
        /** Image / GIF layers. */
        LAYERS("Layers", "layers"),
        /** Freehand pen. */
        DRAW("Draw", "draw"),
        /** Per-clip trim window. */
        TRIM("Trim", "trim"),
        /** Per-clip playback rate. */
        SPEED("Speed", "speed"),
        /** Clip list management (add · reorder · remove). */
        CLIPS("Clips", "clips"),
        /** Enter the timeline workspace. */
        TIMELINE("Timeline", "timeline"),
        /** Per-clip audio volume / mute. */
        VOLUME("Volume", "volume"),
        /** Synth SFX cues. */
        SFX("SFX", "sfx"),
        /** Browse the built-in GIF library (adds frames). */
        GIFS("GIFs", "gifs"),
        /** GIF loop length / frame hold. */
        DURATION("Duration", "duration"),
        /** Mass production: freeze this design as a batch base. */
        BATCH("Batch", "batch"),
        /** Controls / gestures reference (W6 — touch-first "On screen" +
         *  optional keyboard section). Id stays SHORTCUTS for stability. */
        SHORTCUTS("Controls", "shortcuts"),

        // ── Selection actions ───────────────────────────────────────────
        /** Edit the selected overlay (text / style). */
        EDIT("Edit", "edit"),
        /** Visibility window (start / end) for a timed element. */
        TIME_WINDOW("Timing", "time"),
        /** Clone the selection. */
        DUPLICATE("Duplicate", "duplicate"),
        /** Bring forward in the layer stack. */
        FORWARD("Forward", "forward"),
        /** Send backward in the layer stack. */
        BACKWARD("Backward", "backward"),
        /** Split a clip at the playhead. */
        SPLIT("Split", "split"),
        /** Toggle a clip's audio. */
        MUTE("Mute", "mute"),
        /** GIF frame hold time. */
        HOLD("Hold", "hold"),
        /** Move a frame earlier. */
        MOVE_LEFT("Left", "move-left"),
        /** Move a frame later. */
        MOVE_RIGHT("Right", "move-right"),
        /** Enter the timeline workspace from a clip selection. */
        OPEN_TIMELINE("Timeline", "timeline"),
        /** Remove the selection. */
        DELETE("Delete", "delete"),
    }

    /** What the user currently has selected (drives [selectionFor]). */
    enum class SelectionKind { NONE, TEXT, STICKER, IMAGE_LAYER, CLIP, GIF_FRAME, SFX_CUE }

    /** At most this many primary tiles per mode (leaves room for More). */
    const val MAX_PRIMARY_TOOLS = 5

    /**
     * Canonical primary order. Every mode's primary list is a prefix-ordered
     * subsequence of this (invariant 4) — so the bar never reshuffles as a
     * creator switches modes.
     */
    val PRIMARY_ORDER: List<ToolId> =
        listOf(ToolId.MEDIA, ToolId.TEXT, ToolId.STICKER, ToolId.SOUND, ToolId.LOOK)

    /** Modes in which each primary tool is usable. */
    private val primaryModes: Map<ToolId, Set<MemeMode>> = mapOf(
        ToolId.MEDIA to setOf(MemeMode.IMAGE, MemeMode.GIF, MemeMode.VIDEO),
        ToolId.TEXT to setOf(MemeMode.IMAGE, MemeMode.GIF, MemeMode.VIDEO),
        ToolId.STICKER to setOf(MemeMode.IMAGE, MemeMode.GIF, MemeMode.VIDEO),
        // GIF carries no audio track; IMAGE has no timeline to place a cue on.
        ToolId.SOUND to setOf(MemeMode.VIDEO),
        ToolId.LOOK to setOf(MemeMode.IMAGE, MemeMode.GIF, MemeMode.VIDEO),
    )

    /** Advanced tools by mode (More overflow). */
    private val advancedModes: Map<ToolId, Set<MemeMode>> = mapOf(
        ToolId.CAPTIONS to setOf(MemeMode.IMAGE, MemeMode.GIF, MemeMode.VIDEO),
        ToolId.CANVAS to setOf(MemeMode.IMAGE, MemeMode.GIF, MemeMode.VIDEO),
        ToolId.LAYERS to setOf(MemeMode.IMAGE, MemeMode.GIF, MemeMode.VIDEO),
        ToolId.DRAW to setOf(MemeMode.IMAGE, MemeMode.GIF, MemeMode.VIDEO),
        ToolId.TRIM to setOf(MemeMode.VIDEO),
        ToolId.SPEED to setOf(MemeMode.VIDEO),
        ToolId.CLIPS to setOf(MemeMode.VIDEO),
        ToolId.TIMELINE to setOf(MemeMode.VIDEO, MemeMode.GIF),
        ToolId.VOLUME to setOf(MemeMode.VIDEO),
        ToolId.SFX to setOf(MemeMode.VIDEO),
        ToolId.GIFS to setOf(MemeMode.GIF),
        ToolId.DURATION to setOf(MemeMode.GIF),
        ToolId.BATCH to setOf(MemeMode.IMAGE, MemeMode.GIF, MemeMode.VIDEO),
        ToolId.SHORTCUTS to setOf(MemeMode.IMAGE, MemeMode.GIF, MemeMode.VIDEO),
    )

    /** Selection actions by selection kind (shown only while selected). */
    private val selectionActions: Map<SelectionKind, List<ToolId>> = mapOf(
        SelectionKind.NONE to emptyList(),
        SelectionKind.TEXT to listOf(
            ToolId.EDIT, ToolId.TIME_WINDOW, ToolId.DUPLICATE,
            ToolId.FORWARD, ToolId.BACKWARD, ToolId.DELETE,
        ),
        SelectionKind.STICKER to listOf(
            ToolId.TIME_WINDOW, ToolId.DUPLICATE,
            ToolId.FORWARD, ToolId.BACKWARD, ToolId.DELETE,
        ),
        SelectionKind.IMAGE_LAYER to listOf(
            ToolId.TIME_WINDOW, ToolId.DUPLICATE,
            ToolId.FORWARD, ToolId.BACKWARD, ToolId.DELETE,
        ),
        SelectionKind.CLIP to listOf(
            ToolId.OPEN_TIMELINE, ToolId.TRIM, ToolId.SPLIT, ToolId.SPEED,
            ToolId.VOLUME, ToolId.MUTE, ToolId.DUPLICATE, ToolId.DELETE,
        ),
        SelectionKind.GIF_FRAME to listOf(
            ToolId.HOLD, ToolId.MOVE_LEFT, ToolId.MOVE_RIGHT, ToolId.DELETE,
        ),
        SelectionKind.SFX_CUE to listOf(
            ToolId.EDIT, ToolId.DELETE,
        ),
    )

    /** Advanced order (stable regardless of mode). */
    val ADVANCED_ORDER: List<ToolId> = advancedModes.keys.toList()

    /**
     * The always-visible bar for [mode], in canonical order, bounded by
     * [MAX_PRIMARY_TOOLS]. Unknown modes fall back to the IMAGE set — the
     * same lenient-but-bounded rule the rest of the studio contract uses.
     */
    fun primaryFor(mode: MemeMode?): List<ToolId> {
        val resolved = mode ?: MemeMode.IMAGE
        return PRIMARY_ORDER
            .filter { id -> primaryModes[id]?.contains(resolved) == true }
            .take(MAX_PRIMARY_TOOLS)
    }

    /** The More overflow for [mode], in canonical order. */
    fun advancedFor(mode: MemeMode?): List<ToolId> {
        val resolved = mode ?: MemeMode.IMAGE
        return ADVANCED_ORDER.filter { id -> advancedModes[id]?.contains(resolved) == true }
    }

    /** Contextual actions for [kind]; empty for [SelectionKind.NONE]. */
    fun selectionFor(kind: SelectionKind?): List<ToolId> =
        selectionActions[kind ?: SelectionKind.NONE] ?: emptyList()

    /** Tier of a tool id (exactly one, by construction). */
    fun tierOf(id: ToolId): Tier = when {
        id in PRIMARY_ORDER -> Tier.PRIMARY
        id in advancedModes -> Tier.ADVANCED
        else -> Tier.SELECTION
    }

    /**
     * The JSON both platforms render from:
     * `{"maxPrimary":5,
     *   "primary":{"image":[{"id","label","icon"}],"gif":[…],"video":[…]},
     *   "advanced":{"image":[…],"gif":[…],"video":[…]},
     *   "selection":{"text":[…],"sticker":[…],"image_layer":[…]}}`
     *
     * IDs are the enum names lowercased (`"media"`, `"time_window"`, …) so
     * a native `when` switches on a stable token instead of a label.
     */
    fun catalogJson(): String = buildJsonObject {
        fun rows(ids: List<ToolId>) = buildJsonArray {
            ids.forEach { id ->
                add(buildJsonObject {
                    put("id", id.name.lowercase())
                    put("label", id.label)
                    put("icon", id.iconKey)
                })
            }
        }
        put("maxPrimary", MAX_PRIMARY_TOOLS)
        put("primary", buildJsonObject {
            MemeMode.entries.forEach { mode -> put(mode.name.lowercase(), rows(primaryFor(mode))) }
        })
        put("advanced", buildJsonObject {
            MemeMode.entries.forEach { mode -> put(mode.name.lowercase(), rows(advancedFor(mode))) }
        })
        put("selection", buildJsonObject {
            SelectionKind.entries.forEach { kind ->
                put(kind.name.lowercase(), rows(selectionFor(kind)))
            }
        })
    }.toString()
}
