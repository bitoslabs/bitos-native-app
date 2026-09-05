package space.bitos.app.ui.feed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable publish-job ledger (prototype `#/queue`): meme publish attempts
 * persist inputs + stage so a crash never loses the run. Small JSON
 * ledger + one media file per job (deleted on done/discard); bounded.
 */
data class MemePublishJob(
    val id: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val mode: String, // image | gif | video
    val caption: String,
    val altText: String,
    val contentWarningReason: String?,
    val extraTagsJson: String,
    val remixEventId: String,
    val remixAuthor: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val coverThumbUrl: String?,
    val stage: Int, // 0…7 (render…confirm)
    val mediaUrl: String? = null,
    val mediaSha256: String? = null,
    val eventId: String? = null,
    val status: String, // active | failed | done | discarded
    val lastError: String? = null,
    val mediaFile: String,
) {
    val isTerminal: Boolean get() = status == "done" || status == "discarded"
    /** Safe to re-send only before anything signed (an event id may be live). */
    val retryAllowed: Boolean get() = eventId == null && status != "done"
}

class MemePublishJobStore(context: Context) {
    private val dir = File(context.filesDir, "meme-publish-jobs").apply { runCatching { mkdirs() } }
    private val ledger = File(dir, "jobs.json")
    private val cap = 20

    fun list(): List<MemePublishJob> = runCatching {
        val array = JSONArray(ledger.readText())
        List(array.length()) { i -> decode(array.getJSONObject(i)) }
    }.getOrDefault(emptyList())

    fun recoverable(): List<MemePublishJob> = list().filterNot { it.isTerminal }

    fun job(id: Int): MemePublishJob? = list().firstOrNull { it.id == id }

    fun begin(
        mode: String, caption: String, altText: String, cw: String?,
        extraTagsJson: String, remixEventId: String, remixAuthor: String,
        bytes: ByteArray, mime: String, width: Int, height: Int,
        durationMs: Long, coverThumbUrl: String?, nowMs: Long,
    ): Int {
        val id = ((nowMs / 1000) % 10_000).toInt()
        val file = "job-$id-$nowMs.bin"
        runCatching { File(dir, file).writeBytes(bytes) }
        val job = MemePublishJob(
            id, nowMs, nowMs, mode, caption, altText, cw, extraTagsJson,
            remixEventId, remixAuthor, mime, width, height, durationMs,
            coverThumbUrl, 0, status = "active", mediaFile = file,
        )
        persist((listOf(job) + list().filterNot { it.id == id }).take(cap))
        return id
    }

    fun update(
        id: Int, stage: Int? = null, mediaUrl: String? = null,
        sha256: String? = null, eventId: String? = null,
        status: String? = null, error: String? = null, nowMs: Long = System.currentTimeMillis(),
    ) {
        val jobs = list().toMutableList()
        val index = jobs.indexOfFirst { it.id == id }
        if (index < 0) return
        var job = jobs[index]
        stage?.let { job = job.copy(stage = it) }
        mediaUrl?.let { job = job.copy(mediaUrl = it) }
        sha256?.let { job = job.copy(mediaSha256 = it) }
        eventId?.let { job = job.copy(eventId = it) }
        status?.let { job = job.copy(status = it, lastError = if (it == "active") null else job.lastError) }
        error?.let { job = job.copy(lastError = it) }
        jobs[index] = job.copy(updatedAtMs = nowMs)
        persist(jobs)
    }

    fun finish(id: Int) {
        val jobs = list().toMutableList()
        val index = jobs.indexOfFirst { it.id == id }
        if (index < 0) return
        runCatching { File(dir, jobs[index].mediaFile).delete() }
        jobs[index] = jobs[index].copy(status = "done", stage = 8)
        persist(jobs)
    }

    fun discard(id: Int) {
        val jobs = list()
        jobs.firstOrNull { it.id == id }?.let {
            runCatching { File(dir, it.mediaFile).delete() }
        }
        persist(jobs.filterNot { it.id == id })
    }

    fun loadBytes(job: MemePublishJob): ByteArray? =
        runCatching { File(dir, job.mediaFile).readBytes() }.getOrNull()

    /** Stored bytes still hash to the recorded digest? null = media missing. */
    fun integrityOK(job: MemePublishJob): Boolean? {
        val expected = job.mediaSha256 ?: return null
        val bytes = loadBytes(job) ?: return null
        val digest = space.bitos.core.nostr.Sha256EventHasher.sha256(bytes)
            .joinToString("") { String.format("%02x", it.toInt() and 0xff) }
        return digest == expected
    }

    private fun persist(jobs: List<MemePublishJob>) {
        runCatching {
            ledger.writeText(JSONArray().apply { jobs.forEach { put(encode(it)) } }.toString())
        }
    }

    private fun encode(j: MemePublishJob) = JSONObject().apply {
        put("v", 1); put("id", j.id); put("createdAtMs", j.createdAtMs); put("updatedAtMs", j.updatedAtMs)
        put("mode", j.mode); put("caption", j.caption); put("altText", j.altText)
        put("cw", j.contentWarningReason ?: ""); put("extraTagsJson", j.extraTagsJson)
        put("remixEventId", j.remixEventId); put("remixAuthor", j.remixAuthor)
        put("mime", j.mime); put("width", j.width); put("height", j.height); put("durationMs", j.durationMs)
        put("coverThumbUrl", j.coverThumbUrl ?: ""); put("stage", j.stage)
        put("mediaUrl", j.mediaUrl ?: ""); put("sha256", j.mediaSha256 ?: ""); put("eventId", j.eventId ?: "")
        put("status", j.status); put("lastError", j.lastError ?: ""); put("mediaFile", j.mediaFile)
    }

    private fun decode(o: JSONObject) = MemePublishJob(
        id = o.getInt("id"), createdAtMs = o.getLong("createdAtMs"), updatedAtMs = o.getLong("updatedAtMs"),
        mode = o.getString("mode"), caption = o.optString("caption"), altText = o.optString("altText"),
        contentWarningReason = o.optString("cw").ifBlank { null },
        extraTagsJson = o.optString("extraTagsJson"), remixEventId = o.optString("remixEventId"),
        remixAuthor = o.optString("remixAuthor"), mime = o.getString("mime"),
        width = o.optInt("width"), height = o.optInt("height"), durationMs = o.optLong("durationMs"),
        coverThumbUrl = o.optString("coverThumbUrl").ifBlank { null }, stage = o.optInt("stage"),
        mediaUrl = o.optString("mediaUrl").ifBlank { null },
        mediaSha256 = o.optString("sha256").ifBlank { null },
        eventId = o.optString("eventId").ifBlank { null },
        status = o.getString("status"), lastError = o.optString("lastError").ifBlank { null },
        mediaFile = o.getString("mediaFile"),
    )
}
