package space.bitos.app.ui.create.meme

import java.io.File

/**
 * On-disk continuation store for the Quick MEM editor (plan MST-018,
 * EDT-001/002): one dir per slot under `<root>/slots/<slotId>/` holding
 * `slot.json` (shared [space.bitos.core.studio.MemeSlotCodec] wire),
 * copied-in assets (CAP-005: picker URIs are transient — bytes are
 * copied, never referenced) and a `poster.jpg` (~256 px, ≤192 KB). The
 * hub index lives at `<root>/index.json` (shared codec + LRU rules).
 *
 * Platform edges ([BitmapIo], [AssetOpener]) are injected so the
 * round-trip is JVM-testable against a temp dir; production uses
 * [AndroidBitmapIo] + a resolver-backed opener, rooted at
 * `context.filesDir/studio`. Every read is lenient — a corrupt store
 * degrades to "no slots" and never blocks creating.
 */
class MemeProjectStore(
    private val root: File,
    private val bitmaps: BitmapIo = AndroidBitmapIo,
) {

    /** One session asset to persist: project id + opaque source key (uri). */
    data class AssetRef(val id: String, val sourceKey: String)

    /** Opens an asset's bytes by source key; null = unreadable (skipped). */
    fun interface AssetOpener {
        fun open(sourceKey: String): ByteArray?
    }

    /** Bitmap edges the store needs (injected for JVM tests). */
    interface BitmapIo {
        fun aspectOf(bytes: ByteArray): Float?
        fun encodePosterJpeg(bytes: ByteArray, longEdge: Int): ByteArray?
    }

    data class SavedSlot(
        val document: space.bitos.core.studio.MemeSlotDocument,
        /** Asset files keyed by project asset id (resume loads these). */
        val assetFiles: Map<String, File>,
        val posterFile: File?,
    )

    init {
        runCatching { root.mkdirs() }
    }

    // ── Save ─────────────────────────────────────────────────────────────

    /**
     * Persists the slot: copies session assets into the slot dir
     * (idempotent), writes the wire, regenerates the poster, updates the
     * index and LRU-evicts overflow (evicted dirs are deleted here).
     */
    fun save(
        slotId: String,
        projectWire: String,
        assets: List<AssetRef>,
        opener: AssetOpener,
        nowMs: Long,
    ): List<String> {
        val document = space.bitos.core.studio.MemeProjectContract.decode(projectWire)
            ?: return emptyList()
        val slotDir = File(File(root, SLOTS_DIR), slotId).apply { mkdirs() }

        val assetRefs = assets.mapNotNull { ref ->
            val target = File(slotDir, "asset-${ref.id}.img")
            if (!target.exists() || target.length() == 0L) {
                val bytes = opener.open(ref.sourceKey) ?: return@mapNotNull null
                runCatching { target.writeBytes(bytes) }.getOrDefault(Unit)
                if (!target.exists() || target.length() == 0L) return@mapNotNull null
            }
            val bytes = runCatching { target.readBytes() }.getOrNull() ?: return@mapNotNull null
            space.bitos.core.studio.MemeSlotAsset(
                id = ref.id,
                fileName = target.name,
                aspect = bitmaps.aspectOf(bytes) ?: 1f,
            )
        }

        val finalDocument = space.bitos.core.studio.MemeSlotDocument(
            slotId = slotId,
            project = document,
            assets = assetRefs,
            updatedAtMs = nowMs,
        )
        runCatching {
            File(slotDir, SLOT_FILE).writeText(
                space.bitos.core.studio.MemeSlotCodec.encode(finalDocument),
            )
        }

        assetRefs.firstOrNull()?.let { first ->
            File(slotDir, first.fileName).takeIf { it.exists() }?.let { assetFile ->
                runCatching { assetFile.readBytes() }.getOrNull()?.let { bytes ->
                    bitmaps.encodePosterJpeg(bytes, POSTER_LONG_EDGE)?.let { jpeg ->
                        runCatching { File(slotDir, POSTER_FILE).writeBytes(jpeg) }
                    }
                }
            }
        }

        val upsert = space.bitos.core.studio.MemeSlotRules.upsert(
            entries = readIndex(),
            slotId = slotId,
            updatedAtMs = nowMs,
            posterName = POSTER_FILE,
            label = space.bitos.core.studio.MemeSlotRules.labelFor(document),
        )
        writeIndex(upsert.entries)
        upsert.evicted.forEach { evictDir(it) }
        return upsert.evicted
    }

    // ── Read ─────────────────────────────────────────────────────────────

    fun listSlots(): List<space.bitos.core.studio.MemeSlotEntry> = readIndex()

    /** Loads one slot for resume; null when its files are missing/corrupt. */
    fun loadSlot(slotId: String): SavedSlot? {
        val slotDir = File(File(root, SLOTS_DIR), slotId)
        val wire = runCatching { File(slotDir, SLOT_FILE).readText() }.getOrNull() ?: return null
        val document = space.bitos.core.studio.MemeSlotCodec.decode(wire) ?: return null
        val files = document.assets.associate { asset ->
            asset.id to File(slotDir, asset.fileName)
        }
        val poster = File(slotDir, POSTER_FILE).takeIf { it.exists() }
        return SavedSlot(document, files, poster)
    }

    fun posterFile(slotId: String): File? =
        File(File(File(root, SLOTS_DIR), slotId), POSTER_FILE).takeIf { it.exists() }

    // ── Delete ───────────────────────────────────────────────────────────

    /** Removes a slot dir and drops it from the index (explicit deletes only). */
    fun deleteSlot(slotId: String) {
        evictDir(slotId)
        writeIndex(readIndex().filter { it.slotId != slotId })
    }

    private fun evictDir(slotId: String) {
        runCatching { File(File(root, SLOTS_DIR), slotId).deleteRecursively() }
    }

    private fun readIndex(): List<space.bitos.core.studio.MemeSlotEntry> =
        runCatching {
            val indexFile = File(root, INDEX_FILE)
            if (!indexFile.exists()) return emptyList()
            space.bitos.core.studio.MemeSlotCodec.decodeIndex(indexFile.readText())
        }.getOrDefault(emptyList())

    private fun writeIndex(entries: List<space.bitos.core.studio.MemeSlotEntry>) {
        runCatching {
            File(root, INDEX_FILE).writeText(
                space.bitos.core.studio.MemeSlotCodec.encodeIndex(entries),
            )
        }
    }

    companion object {
        const val SLOTS_DIR = "slots"
        const val INDEX_FILE = "index.json"
        const val SLOT_FILE = "slot.json"
        const val POSTER_FILE = "poster.jpg"
        const val POSTER_LONG_EDGE = 256
    }
}

/** Production bitmap edges: BitmapFactory decode + scaled JPEG poster. */
object AndroidBitmapIo : MemeProjectStore.BitmapIo {
    override fun aspectOf(bytes: ByteArray): Float? = runCatching {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth.toFloat() / options.outHeight
        } else {
            null
        }
    }.getOrNull()

    override fun encodePosterJpeg(bytes: ByteArray, longEdge: Int): ByteArray? = runCatching {
        val source = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return null
        val scale = (longEdge.toFloat() / maxOf(source.width, source.height)).coerceAtMost(1f)
        val poster = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            source
        }
        val out = java.io.ByteArrayOutputStream()
        poster.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        if (poster !== source) poster.recycle()
        out.toByteArray()
    }.getOrNull()
}
