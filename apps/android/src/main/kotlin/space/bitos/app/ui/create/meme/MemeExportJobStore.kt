package space.bitos.app.ui.create.meme

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable export jobs (MUX-05): the rendered artifact is persisted BEFORE
 * the destination save, so an interrupted save retries from the artifact
 * without re-rendering. A job stuck in `saving` on relaunch becomes
 * needsReview — MediaStore saves cannot be reconciled back reliably, so
 * the honest recovery asks the user to check. Thread-safe (called from IO).
 */
class MemeExportJobStore(context: Context) {
    private val dir = File(context.filesDir, "meme-export-jobs").apply { runCatching { mkdirs() } }
    private val ledger = File(dir, "jobs.json")
    private val cap = 10

    data class Job(
        val id: Int, val format: String, val phase: String,
        val artifactFile: String, val artifactBytes: Int, val lastError: String?,
    )

    @Synchronized fun list(): List<Job> = runCatching {
        val array = JSONArray(ledger.readText())
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            val phase = o.getString("phase")
            Job(
                id = o.getInt("id"), format = o.getString("format"),
                // Crash-mid-save reconciliation: ask, don't assume.
                phase = if (phase == "saving") "needsReview" else phase,
                artifactFile = o.getString("artifactFile"),
                artifactBytes = o.optInt("artifactBytes"),
                lastError = o.optString("lastError").ifBlank { null },
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized fun recoverable(): List<Job> =
        list().filter { it.phase == "rendered" || it.phase == "needsReview" || it.phase == "failed" }

    private fun persist(jobs: List<Job>) {
        runCatching {
            ledger.writeText(JSONArray().apply {
                jobs.forEach {
                    put(JSONObject().apply {
                        put("id", it.id); put("format", it.format); put("phase", it.phase)
                        put("artifactFile", it.artifactFile); put("artifactBytes", it.artifactBytes)
                        put("lastError", it.lastError ?: "")
                    })
                }
            }.toString())
        }
    }

    @Synchronized fun begin(format: String): Int {
        val id = ((System.currentTimeMillis() / 1000) % 10_000).toInt()
        val job = Job(id, format, "rendering", "export-$id.bin", 0, null)
        persist(listOf(job) + list().filterNot { it.id == id }.take(cap - 1))
        return id
    }

    /** Persist-then-effect: the artifact lands on disk before the save. */
    @Synchronized fun artifactReady(id: Int, bytes: ByteArray): Boolean {
        val jobs = list().toMutableList()
        val index = jobs.indexOfFirst { it.id == id }
        if (index < 0) return false
        val ok = runCatching { File(dir, jobs[index].artifactFile).writeBytes(bytes) }.isSuccess
        if (ok) jobs[index] = jobs[index].copy(phase = "rendered", artifactBytes = bytes.size)
        persist(jobs)
        return ok
    }

    @Synchronized fun update(id: Int, phase: String? = null, error: String? = null) {
        val jobs = list().toMutableList()
        val index = jobs.indexOfFirst { it.id == id }
        if (index < 0) return
        var job = jobs[index]
        phase?.let { job = job.copy(phase = it) }
        error?.let { job = job.copy(lastError = it) }
        jobs[index] = job
        persist(jobs)
    }

    @Synchronized fun finish(id: Int) {
        val jobs = list()
        jobs.firstOrNull { it.id == id }?.let { runCatching { File(dir, it.artifactFile).delete() } }
        persist(jobs.filterNot { it.id == id })
    }

    @Synchronized fun discard(id: Int) = finish(id)

    @Synchronized fun loadArtifact(id: Int): Pair<ByteArray, String>? {
        val job = list().firstOrNull { it.id == id } ?: return null
        val bytes = runCatching { File(dir, job.artifactFile).readBytes() }.getOrNull() ?: return null
        return bytes to job.format
    }
}
