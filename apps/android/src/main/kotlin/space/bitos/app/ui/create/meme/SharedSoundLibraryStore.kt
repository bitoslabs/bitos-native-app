package space.bitos.app.ui.create.meme

import java.io.File
import space.bitos.core.studio.SharedSoundContract
import space.bitos.core.studio.SharedSoundLibrary

/**
 * Local shared-sound library persistence (MST-047 W4b, plan §3.5):
 * `studio/sounds/index.json` (the shared, common-tested codec — LRU,
 * ≤ 30 entries) plus one `<id>.bin` per saved audio artifact. Save is
 * gated by the shared `ingestCheck` (≤ 15 s decoded, ≤ 8 MB); junk never
 * reaches disk. Re-attach verifies the bytes against the entry sha
 * before anything mixes — same rule as the relay download path.
 */
class SharedSoundLibraryStore(dir: File) {

    private val root = File(dir, "sounds").apply { mkdirs() }
    private val indexFile = File(root, "index.json")

    fun list(): List<SharedSoundLibrary.Entry> =
        SharedSoundLibrary.decode(indexFile.takeIf { it.exists() }?.readText() ?: "").entries

    /** Saved bytes for one entry; null when the artifact is gone
     *  (evicted externally) — callers degrade to "re-download by URL". */
    fun bytes(id: String): ByteArray? {
        val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        if (safe.isBlank() || safe != id) return null
        val file = File(root, "$safe.bin")
        return file.takeIf { it.exists() }?.readBytes()
    }

    /**
     * Adds (or replaces by id) one sound with its bytes. Returns false —
     * leaving the index untouched — when the shared ingest gate rejects
     * the decoded duration/size pair or the entry shape is unusable.
     */
    fun save(
        entry: SharedSoundLibrary.Entry,
        audioBytes: ByteArray,
    ): Boolean {
        if (!SharedSoundContract.ingestCheck(entry.durationMs, audioBytes.size.toLong())) return false
        val current = SharedSoundLibrary.decode(readIndex())
        val next = SharedSoundLibrary.add(current, entry)
        // LRU eviction must delete the dropped artifact files too.
        val evicted = current.entries.map { it.id } - next.entries.map { it.id }.toSet()
        val safeId = entry.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        if (safeId.isBlank() || safeId != entry.id) return false
        return try {
            File(root, "$safeId.bin").writeBytes(audioBytes)
            evicted.forEach { id ->
                val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
                if (safe == id) File(root, "$safe.bin").takeIf { it.exists() }?.delete()
            }
            writeIndex(SharedSoundLibrary.encode(next))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun remove(id: String) {
        val current = SharedSoundLibrary.decode(readIndex())
        val next = SharedSoundLibrary.remove(current, id)
        val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        if (safe == id && safe.isNotBlank()) File(root, "$safe.bin").takeIf { it.exists() }?.delete()
        writeIndex(SharedSoundLibrary.encode(next))
    }

    private fun readIndex(): String = indexFile.takeIf { it.exists() }?.readText() ?: ""

    private fun writeIndex(json: String) {
        runCatching { indexFile.writeText(json) }
    }
}
