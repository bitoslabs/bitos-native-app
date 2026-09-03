package space.bitos.core.studio

import space.bitos.core.nostr.Sha256EventHasher

/**
 * Pure mass-production decisions (product doc §3–§5, mockup scr-batch /
 * scr-review / scr-pub). Everything here is deterministic and side-effect
 * free: typed-slot validation with the mockup's severity contract
 * (blocker = cannot queue, warning = reviewable), placeholder resolution
 * into variants, output naming, approval binding to content+poster hashes,
 * recipe fork-on-edit immutability, the approved publish-queue order
 * (per-event signing, stable row order) and bounded CSV import.
 */
object MassBatchRules {

    // ── Validation ──────────────────────────────────────────────────────

    enum class Severity { OK, WARN, BLOCKER }

    data class Note(val slotId: String?, val kind: Kind, val message: String) {
        enum class Kind { MISSING, UNREADABLE_ASSET, COERCED, INVALID, OVERFLOW }
    }

    data class RowValidation(val rowId: String, val severity: Severity, val notes: List<Note>) {
        val queueable: Boolean get() = severity != Severity.BLOCKER
    }

    /**
     * One row against its recipe. `assetReadable` reports whether a stored
     * asset file actually resolves (mockup: "image_asset unreadable —
     * cannot queue" is a blocker; overflow is only a warning).
     */
    fun validateRow(
        recipe: MassRecipe,
        row: MassRow,
        assetReadable: (fileName: String) -> Boolean = { true },
    ): RowValidation {
        val notes = mutableListOf<Note>()
        var severity = Severity.OK

        fun warn(note: Note) {
            notes += note
            if (severity == Severity.OK) severity = Severity.WARN
        }
        fun block(note: Note) {
            notes += note
            severity = Severity.BLOCKER
        }

        recipe.slots.forEach { slot ->
            val raw = row.values[slot.id]?.trim().orEmpty()
            when (slot.type) {
                MassSlotType.SHORT_TEXT, MassSlotType.LONG_TEXT -> {
                    val cap = if (slot.type == MassSlotType.SHORT_TEXT) slot.maxLen else MassBatch.MAX_LONG_TEXT
                    if (raw.isEmpty()) {
                        if (slot.required) block(Note(slot.id, Note.Kind.MISSING, "row is missing '${slot.name}'"))
                    } else if (raw.length > cap) {
                        warn(Note(slot.id, Note.Kind.INVALID, "'${slot.name}' over ${cap} chars will be clamped"))
                    }
                }
                MassSlotType.NUMBER -> {
                    if (raw.isEmpty()) {
                        if (slot.required) block(Note(slot.id, Note.Kind.MISSING, "row is missing '${slot.name}'"))
                    } else {
                        val coerced = coerceNumber(raw)
                        when {
                            coerced == null -> {
                                if (slot.required) block(Note(slot.id, Note.Kind.INVALID, "'${slot.name}' is not a number"))
                                else warn(Note(slot.id, Note.Kind.INVALID, "'${slot.name}' is not a number — dropped"))
                            }
                            coerced.coerced -> warn(Note(slot.id, Note.Kind.COERCED, "'${raw}' coerced to ${groupedNumber(coerced.value)}"))
                            coerced.value < slot.min || coerced.value > slot.max ->
                                warn(Note(slot.id, Note.Kind.INVALID, "'${slot.name}' outside ${formatNumber(slot.min)}–${formatNumber(slot.max)} — clamped"))
                        }
                    }
                }
                MassSlotType.COLOR -> {
                    if (raw.isEmpty()) {
                        if (slot.required) block(Note(slot.id, Note.Kind.MISSING, "row is missing '${slot.name}'"))
                    } else if (!Regex("^#[0-9a-fA-F]{6}$").matches(raw)) {
                        warn(Note(slot.id, Note.Kind.INVALID, "'${raw}' is not a #rrggbb color — falls back to white"))
                    }
                }
                MassSlotType.ENUM -> {
                    if (raw.isEmpty()) {
                        if (slot.required) block(Note(slot.id, Note.Kind.MISSING, "row is missing '${slot.name}'"))
                    } else if (slot.enumValues.isNotEmpty() && raw !in slot.enumValues) {
                        warn(Note(slot.id, Note.Kind.INVALID, "'${raw}' is not one of ${slot.enumValues.joinToString("/")} — falls back"))
                    }
                }
                MassSlotType.TIMESTAMP -> {
                    if (raw.isEmpty()) {
                        if (slot.required) block(Note(slot.id, Note.Kind.MISSING, "row is missing '${slot.name}'"))
                    } else if (raw.toLongOrNull() == null && !Regex("^\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2})?)?$").matches(raw)) {
                        warn(Note(slot.id, Note.Kind.INVALID, "'${raw}' is not a timestamp — dropped"))
                    }
                }
                MassSlotType.IMAGE_ASSET, MassSlotType.VIDEO_ASSET, MassSlotType.AUDIO_ASSET -> {
                    val file = row.assetFiles[slot.id]
                    when {
                        file.isNullOrBlank() ->
                            if (slot.required) block(Note(slot.id, Note.Kind.MISSING, "row ${row.id} missing ${slot.name} asset"))
                        !assetReadable(file) ->
                            block(Note(slot.id, Note.Kind.UNREADABLE_ASSET, "${slot.name} unreadable — cannot queue"))
                        else -> Unit
                    }
                }
            }
        }

        // Mockup: text overflow vs the canvas is a WARNING ("no fit policy
        // allowed"), never a silent clamp — review catches it.
        resolvedProject(recipe, row).overlays.forEach { overlay ->
            val rawWidth = overlay.text.length * overlay.size * overlay.scale * 0.6f / 1080f
            if (rawWidth > 1f) {
                warn(Note(null, Note.Kind.OVERFLOW, "row ${row.id}: text overflow — canvas has no fit policy allowed"))
            }
        }

        return RowValidation(row.id, severity, notes)
    }

    fun validateAll(
        recipe: MassRecipe,
        rows: List<MassRow>,
        assetReadable: (fileName: String) -> Boolean = { true },
    ): Map<String, RowValidation> = rows.associate { it.id to validateRow(recipe, it, assetReadable) }

    /** "1k" → 1_000, "2.5m" → 2_500_000; null = not numeric at all. */
    data class CoercedNumber(val value: Double, val coerced: Boolean)

    fun coerceNumber(raw: String): CoercedNumber? {
        val trimmed = raw.trim().lowercase().replace(",", "")
        trimmed.toDoubleOrNull()?.let { return CoercedNumber(it, false) }
        val match = Regex("^([0-9]*\\.?[0-9]+)\\s*(k|m|b)$").find(trimmed) ?: return null
        val base = match.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2]) {
            "k" -> 1_000.0
            "m" -> 1_000_000.0
            else -> 1_000_000_000.0
        }
        return CoercedNumber(base * multiplier, true)
    }

    fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    /** Display grouping: 2500 → "2,500" (mockup: "1k coerced to 1,000"). */
    fun groupedNumber(value: Double): String {
        val text = formatNumber(value)
        val sign = if (text.startsWith("-")) "-" else ""
        val digits = text.removePrefix("-")
        if (!digits.all { it.isDigit() }) return text
        return sign + digits.reversed().chunked(3).joinToString(",").reversed()
    }

    // ── Resolution ──────────────────────────────────────────────────────

    /** A fully resolved output: what rendering/publishing consumes. */
    data class ResolvedVariant(
        val rowId: String,
        /** 1-based stable order (never reshuffles mid-review — mockup). */
        val index: Int,
        val project: MemeProject,
        /** Project asset id → row asset file (IMAGE_ASSET substitution). */
        val assetOverrides: Map<String, String>,
        val name: String,
        val caption: String,
    )

    /** Substitutes `{slotId}` / `{i}` placeholders in any text. */
    fun substitute(text: String, row: MassRow, recipe: MassRecipe, index: Int): String {
        var out = text.replace("{i}", index.toString())
        recipe.slots.forEach { slot ->
            out = out.replace("{${slot.id}}", displayValue(recipe, row, slot.id))
        }
        return out
    }

    /** The display value a placeholder renders (coerced/fallback applied). */
    fun displayValue(recipe: MassRecipe, row: MassRow, slotId: String): String {
        val slot = recipe.slots.firstOrNull { it.id == slotId } ?: return ""
        val raw = row.values[slotId]?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return when (slot.type) {
            MassSlotType.NUMBER -> coerceNumber(raw)?.let {
                groupedNumber(it.value.coerceIn(slot.min, slot.max))
            } ?: ""
            MassSlotType.COLOR ->
                if (Regex("^#[0-9a-fA-F]{6}$").matches(raw)) raw.uppercase() else ""
            MassSlotType.ENUM ->
                if (slot.enumValues.isEmpty() || raw in slot.enumValues) raw
                else slot.enumValues.firstOrNull() ?: raw
            MassSlotType.TIMESTAMP -> raw
            else -> raw.take(if (slot.type == MassSlotType.SHORT_TEXT) slot.maxLen else MassBatch.MAX_LONG_TEXT)
        }
    }

    fun resolvedProject(recipe: MassRecipe, row: MassRow, index: Int = 1): MemeProject =
        recipe.project.copy(
            overlays = recipe.project.overlays.map { overlay ->
                val overridden = row.overrideText[overlay.id]?.take(MassBatch.MAX_LONG_TEXT)
                overlay.copy(
                    text = substitute(overridden ?: overlay.text, row, recipe, index),
                )
            },
            altText = substitute(recipe.altText.ifBlank { recipe.project.altText }, row, recipe, index),
            tags = recipe.project.tags,
        )

    fun resolveVariant(recipe: MassRecipe, row: MassRow, index: Int): ResolvedVariant {
        val overrides = recipe.slots
            .filter { it.type == MassSlotType.IMAGE_ASSET && !it.assetTargetId.isNullOrBlank() }
            .mapNotNull { slot ->
                row.assetFiles[slot.id]?.takeIf { it.isNotBlank() }?.let { file ->
                    slot.assetTargetId!! to file
                }
            }
            .toMap()
        return ResolvedVariant(
            rowId = row.id,
            index = index,
            project = resolvedProject(recipe, row, index),
            assetOverrides = overrides,
            name = fileNameFor(substitute(recipe.naming, row, recipe, index)),
            caption = substitute(recipe.caption, row, recipe, index).take(MassBatch.MAX_CAPTION),
        )
    }

    /** Sanitized output file name: [A-Za-z0-9_-], ≤64, never empty. */
    fun fileNameFor(raw: String): String {
        val cleaned = raw.replace(Regex("[^A-Za-z0-9_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(MassBatch.MAX_NAMING_LENGTH)
        return cleaned.ifBlank { "meme" }
    }

    // ── Approval binding ────────────────────────────────────────────────

    /**
     * Deterministic variant identity: recipe version + row values + the
     * resolved project wire. Approvals bind THIS plus the poster hash, so
     * any edit (row value, recipe fork, override) invalidates silently
     * stale approvals (product doc §3 "explicit decision tied to variant
     * content and metadata hashes").
     */
    fun contentHash(recipeVersion: Int, row: MassRow, resolved: MemeProject): String {
        val canonical = buildString {
            append("v1|recipe=").append(recipeVersion)
            append("|row=").append(row.id)
            row.values.keys.sorted().forEach { key -> append('|').append(key).append('=').append(row.values[key]) }
            row.assetFiles.keys.sorted().forEach { key -> append("|asset=").append(key).append('=').append(row.assetFiles[key]) }
            row.overrideText.keys.sorted().forEach { key -> append("|ovr=").append(key) }
            append("|project=").append(MemeProjectContract.encode(resolved))
        }
        return Sha256EventHasher.sha256(canonical.encodeToByteArray()).toHex()
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val v = byte.toInt() and 0xff
            out.append(digits[v ushr 4]).append(digits[v and 0x0f])
        }
        return out.toString()
    }

    /** True while the stored approval still matches current content. */
    fun approvalValid(state: MassVariantState, contentHash: String, posterHash: String?): Boolean {
        val approved = state.approvedHash ?: return false
        if (approved.length != 64) return false
        // The approval hash embeds content + poster at approve time.
        return approved == approvalHash(contentHash, posterHash)
    }

    fun approvalHash(contentHash: String, posterHash: String?): String =
        Sha256EventHasher.sha256(
            "approval|$contentHash|${posterHash ?: "no-poster"}".encodeToByteArray(),
        ).toHex()

    /** Records a completed preview render (native side hashes the poster). */
    fun withRender(
        states: Map<String, MassVariantState>,
        rowId: String,
        posterName: String,
        posterHash: String,
    ): Map<String, MassVariantState> {
        val state = states[rowId] ?: MassVariantState(rowId = rowId)
        return states + (
            rowId to state.copy(
                render = MassRenderState.RENDERED,
                posterName = posterName.take(MassBatch.MAX_ASSET_FILE_LENGTH),
                posterHash = posterHash.take(64),
            )
            )
    }

    fun withApproval(
        states: Map<String, MassVariantState>,
        rowId: String,
        contentHash: String?,
        posterHash: String?,
        nowMs: Long,
    ): Map<String, MassVariantState> {
        val state = states[rowId] ?: MassVariantState(rowId = rowId)
        val next = if (contentHash == null) {
            state.copy(approvedHash = null, approvedAtMs = 0)
        } else {
            state.copy(approvedHash = approvalHash(contentHash, posterHash), approvedAtMs = nowMs)
        }
        return states + (rowId to next)
    }

    // ── Recipe immutability (product doc §3) ────────────────────────────

    /**
     * Fork-on-edit: once a batch holds rows the recipe is frozen; an edit
     * bumps version and drops every render/approval (variants generated
     * from an older recipe are never silently reused). Publish results
     * survive — published history is immutable.
     */
    fun forkRecipe(document: MassBatchDocument, edited: MassRecipe): MassBatchDocument =
        document.copy(
            recipe = edited.copy(version = (document.recipe.version + 1).coerceAtMost(999)),
            states = document.states.mapValues { (_, state) ->
                if (state.publish == MassPublishState.PUBLISHED) state
                else state.copy(
                    render = MassRenderState.PENDING,
                    posterName = null,
                    posterHash = null,
                    approvedHash = null,
                    approvedAtMs = 0,
                    publish = MassPublishState.WAITING,
                    failure = null,
                )
            },
        )

    // ── Publish queue ───────────────────────────────────────────────────

    /**
     * The publish cursor: approved variants whose approval is still valid,
     * not yet published, in STABLE row order (mockup scr-review: ordering
     * never reshuffles mid-review). Signing stays per event — the native
     * runner publishes one at a time and persists after each.
     */
    data class QueueEntry(
        val rowId: String,
        val index: Int,
        val variant: ResolvedVariant,
        val contentHash: String,
    )

    fun queue(
        document: MassBatchDocument,
        validations: Map<String, RowValidation> = validateAll(document.recipe, document.rows),
    ): List<QueueEntry> =
        document.rows
            .withIndex()
            .mapNotNull { (i, row) ->
                val state = document.states[row.id] ?: return@mapNotNull null
                // PUBLISHED stays settled; PUBLISHING is owned by the
                // native runner (crash recovery resets orphans back).
                if (state.publish == MassPublishState.PUBLISHED ||
                    state.publish == MassPublishState.PUBLISHING
                ) {
                    return@mapNotNull null
                }
                if (validations[row.id]?.queueable != true) return@mapNotNull null
                val variant = resolveVariant(document.recipe, row, i + 1)
                val hash = contentHash(document.recipe.version, row, variant.project)
                if (!approvalValid(state, hash, state.posterHash)) return@mapNotNull null
                QueueEntry(row.id, i + 1, variant, hash)
            }

    fun withPublishState(
        states: Map<String, MassVariantState>,
        rowId: String,
        publish: MassPublishState,
        eventId: String? = null,
        failure: String? = null,
    ): Map<String, MassVariantState> {
        val state = states[rowId] ?: MassVariantState(rowId = rowId)
        return states + (
            rowId to state.copy(
                publish = publish,
                publishedEventId = eventId ?: state.publishedEventId,
                failure = when {
                    failure != null -> failure
                    publish == MassPublishState.PUBLISHED || publish == MassPublishState.WAITING -> null
                    else -> state.failure
                },
            )
            )
    }

    // ── CSV import (product doc §4: local CSV, typed column mapping) ────

    data class CsvImport(
        val rows: List<MassRow>,
        /** Non-fatal import notes (unmapped columns etc.), bounded. */
        val notes: List<String>,
    )

    /**
     * RFC-4180-ish bounded parse: quoted cells, embedded commas/newlines,
     * "" escapes. Header maps columns → slot ids (exact id, then slot name
     * case-insensitive); unmapped columns are ignored with a note. Rows
     * cap at [MassBatch.MAX_ROWS].
     */
    fun importCsv(text: String, recipe: MassRecipe, rowIdPrefix: String = "r"): CsvImport {
        if (text.length > MassBatch.MAX_CSV_BYTES) {
            return CsvImport(emptyList(), listOf("CSV exceeds ${MassBatch.MAX_CSV_BYTES / 1024} KB — import refused"))
        }
        val table = parseCsvTable(text)
        if (table.isEmpty()) return CsvImport(emptyList(), listOf("CSV is empty"))
        val header = table.first()
        val notes = mutableListOf<String>()
        val mapping = header.mapIndexed { index, column ->
            val clean = column.trim()
            val slot = recipe.slots.firstOrNull { it.id.equals(clean, ignoreCase = true) }
                ?: recipe.slots.firstOrNull { it.name.equals(clean, ignoreCase = true) }
            if (slot == null && clean.isNotBlank() && index < 24) {
                notes += "column '${clean.take(24)}' has no matching slot — ignored"
            }
            slot?.id
        }
        val rows = table.drop(1)
            .filter { cells -> cells.any { it.isNotBlank() } }
            .take(MassBatch.MAX_ROWS)
            .mapIndexed { index, cells ->
                val values = mapping.mapIndexedNotNull { column, slotId ->
                    val cell = cells.getOrNull(column)?.trim().orEmpty()
                    if (slotId != null && cell.isNotEmpty()) slotId to cell.take(MassBatch.MAX_VALUE_LENGTH) else null
                }.toMap()
                MassRow(id = "$rowIdPrefix${index + 1}", values = values)
            }
        return CsvImport(rows, notes.take(8))
    }

    /** Minimal RFC-4180 table parse (quotes, commas, CRLF, "" escape). */
    internal fun parseCsvTable(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < text.length) {
            val c = text[index]
            when {
                inQuotes && c == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    cell.append('"'); index++
                }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> {
                    cells += cell.toString(); cell.clear()
                }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    cells += cell.toString(); cell.clear()
                    rows += cells.toList(); cells.clear()
                }
                else -> cell.append(c)
            }
            index++
        }
        if (cell.isNotEmpty() || cells.isNotEmpty()) {
            cells += cell.toString()
            rows += cells.toList()
        }
        return rows.filterNot { row -> row.all { it.isBlank() } }
    }
}
