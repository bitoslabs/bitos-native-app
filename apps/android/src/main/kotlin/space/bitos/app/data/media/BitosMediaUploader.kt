package space.bitos.app.data.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import space.bitos.core.identity.IdentitySigner
import space.bitos.core.model.UploadedMedia
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.studio.MemeUploadRouting
import java.util.concurrent.TimeUnit

/** BitOS API upload with a read-back hash check for its canonical media URL. */
class BitosMediaUploader(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    class UploadFailure(reason: String) : Exception(reason)

    /** [onProgress] reports REAL bytes handed to the socket on the POST. */
    suspend fun upload(
        bytes: ByteArray,
        mimeType: String,
        filename: String = "meme.mp4",
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
    ): UploadedMedia =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty() || bytes.size.toLong() > MemeUploadRouting.BITOS_API_MAX_BYTES) {
                throw UploadFailure("file out of bounds")
            }
            val hash = hash(bytes)
            val response = http.newCall(
                Request.Builder()
                    .url(API_URL)
                    .header("X-Upload-Filename", filename)
                    .header("X-SHA-256", hash)
                    .post(BlossomUploader.ProgressRequestBody(bytes, mimeType.toMediaType(), onProgress))
                    .build(),
            ).execute().use { httpResponse ->
                val body = httpResponse.body?.string().orEmpty()
                if (!httpResponse.isSuccessful) throw UploadFailure("BitOS upload failed: ${httpResponse.code} ${body.take(160)}")
                body
            }
            val payload = runCatching { JSONObject(response) }.getOrElse { throw UploadFailure("BitOS upload returned invalid JSON") }
            val url = payload.optString("url").takeIf { it.startsWith("https://") }
                ?: throw UploadFailure("BitOS upload returned no HTTPS media URL")
            // The deployed API does not yet echo a content digest. Never sign
            // based only on its descriptor: verify the public object bytes.
            val stored = http.newCall(Request.Builder().url(url).get().build()).execute().use { remote ->
                if (!remote.isSuccessful) throw UploadFailure("BitOS upload could not be verified: ${remote.code}")
                remote.body?.bytes() ?: throw UploadFailure("BitOS upload verification had no body")
            }
            if (!hash(stored).equals(hash, ignoreCase = true)) {
                throw UploadFailure("BitOS stored different bytes than were uploaded")
            }
            UploadedMedia(url, hash, payload.optString("mimeType").ifBlank { mimeType }, bytes.size.toLong())
        }

    private fun hash(bytes: ByteArray): String = Sha256EventHasher.sha256(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val API_URL = "https://social.bitos.space/api/media/upload"
    }
}

/** Executes the shared video route. A small-video replica failure blocks signing. */
class MemeVideoUploader(
    private val bitos: BitosMediaUploader = BitosMediaUploader(),
    private val blossom: BlossomUploader = BlossomUploader(),
) {
    enum class UploadStage { HASHING, UPLOADING_BITOS, VERIFYING_BITOS, UPLOADING_BLOSSOM, VERIFYING_BLOSSOM }

    suspend fun upload(
        bytes: ByteArray,
        mimeType: String,
        signer: IdentitySigner,
        blossomServerUrl: String,
        onStage: ((UploadStage) -> Unit)? = null,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
    ): UploadedMedia {
        val route = MemeUploadRouting.destinations(mimeType, bytes.size.toLong())
        if (route.isEmpty()) throw BitosMediaUploader.UploadFailure("unsupported video upload size")
        // Dual-leg fraction: each leg's inner progress is REAL socket
        // bytes; the fixed 75/25 weights only stitch the two legs into one
        // monotonic fraction (the replica exists solely for small videos).
        val replicaRuns = MemeUploadRouting.Destination.BLOSSOM in route
        val canonicalSpan = if (replicaRuns) 0.75 else 1.0
        onStage?.invoke(UploadStage.HASHING)
        onStage?.invoke(UploadStage.UPLOADING_BITOS)
        val canonical = bitos.upload(bytes, mimeType) { written, total ->
            onProgress?.invoke(
                (written.toDouble() / total.coerceAtLeast(1).toDouble() * canonicalSpan).toLong(),
                total,
            )
        }
        onStage?.invoke(UploadStage.VERIFYING_BITOS)
        if (replicaRuns) {
            // Name the leg in failures: both legs surface under the same
            // "Upload" machine step, so the error must say WHICH one died.
            try {
                val replica = blossom.upload(bytes, mimeType, signer, blossomServerUrl, onStage = { stage ->
                    onStage?.invoke(
                        if (stage == BlossomUploader.UploadStage.VERIFYING) UploadStage.VERIFYING_BLOSSOM
                        else UploadStage.UPLOADING_BLOSSOM,
                    )
                }, onProgress = { written, total ->
                    val inner = written.toDouble() / total.coerceAtLeast(1).toDouble()
                    onProgress?.invoke(
                        (canonicalSpan + inner * (1.0 - canonicalSpan)).toLong().coerceAtMost(total),
                        total,
                    )
                })
                return canonical.copy(fallbackUrls = listOf(replica.url))
            } catch (failure: Exception) {
                throw BitosMediaUploader.UploadFailure("Blossom replica: ${failure.message}")
            }
        }
        return canonical
    }
}
