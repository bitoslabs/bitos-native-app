package space.bitos.app.data.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import space.bitos.core.identity.IdentitySigner
import space.bitos.core.model.Blossom
import space.bitos.core.model.UploadedMedia
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.publish.NoteComposer
import java.util.concurrent.TimeUnit

/**
 * Blossom (BUD-02/11) uploader: hash → signed kind-24242 auth → Base64url
 * `Authorization: Nostr` header → `PUT {server}/upload` → verify the
 * returned hash matches the local one. Hash mismatch is security-visible
 * and blocking (PUB-002/006): the pipeline refuses to build a descriptor
 * from an unverified upload.
 */
class BlossomUploader(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {

    class UploadFailure(reason: String) : Exception(reason)

    /** Real pipeline checkpoints (drives the publish machine stepper). */
    enum class UploadStage { HASHING, UPLOADING, VERIFYING }

    /**
     * Uploads [bytes] and returns the verified descriptor. The SHA-256 is
     * computed locally first and checked against the server's response.
     *
     * BUD-02/11 flow: sign the kind-24242 auth upfront (production servers
     * reject the unauthenticated probe with 400, not the 401 challenge of
     * the legacy spec draft), `PUT {server}/upload` with the Base64url
     * `Authorization: Nostr` token, accept 200/201.
     *
     * [onProgress] reports REAL bytes handed to the socket during the PUT
     * (written, total) from the IO thread — never estimated.
     */
    suspend fun upload(
        bytes: ByteArray,
        mimeType: String,
        signer: IdentitySigner,
        serverUrl: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        onStage: ((UploadStage) -> Unit)? = null,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
    ): UploadedMedia = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size > Blossom.MAX_FILE_BYTES) throw UploadFailure("file out of bounds")
        onStage?.invoke(UploadStage.HASHING)
        val localHash = Sha256EventHasher.sha256(bytes)
            .joinToString("") { ((it.toInt() and 0xf0) ushr 4).toString(16) + (it.toInt() and 0x0f).toString(16) }
        val endpoint = Blossom.uploadUrl(serverUrl)
        // Fallback when a server never sends a 401 challenge: now + 10 min,
        // clamped into the window [3]composeUploadAuth enforces.
        val expiration = nowSeconds + 600

        // 1. Compose + sign the kind-24242 auth event.
        val composer = NoteComposer(clock = { nowSeconds })
        val auth = composer.composeUploadAuth(
            authorPubkey = signer.publicKeyHex(),
            serverUrl = serverUrl,
            fileHashHex = localHash,
            sizeBytes = bytes.size.toLong(),
            expirationSeconds = expiration,
            nowSeconds = nowSeconds,
        ) ?: throw UploadFailure("auth event rejected")
        val signature = signer.sign(auth.messageBytes()) ?: throw UploadFailure("signing refused")
        val authHeader = Blossom.authorizationHeaderValue(
            composer.signedEventJson(auth, signature) ?: throw UploadFailure("auth frame rejected"),
        ) ?: throw UploadFailure("auth header rejected")

        // 2. Authenticated PUT /upload (BUD-02: 201 new, 200 already stored).
        onStage?.invoke(UploadStage.UPLOADING)
        val body = http.newCall(
            Request.Builder()
                .url(endpoint)
                .header("Authorization", authHeader)
                .header("X-SHA-256", localHash)
                .put(ProgressRequestBody(bytes, mimeType.toMediaType(), onProgress))
                .build(),
        ).execute().use { response ->
            if (response.code != 200 && response.code != 201) {
                val reason = response.header("X-Reason") ?: response.body?.string()?.take(200)
                throw UploadFailure("upload failed: ${response.code}${reason?.let { " $it" } ?: ""}")
            }
            response.body?.string()?.takeIf { it.length <= 65_536 } ?: throw UploadFailure("empty server response")
        }

        onStage?.invoke(UploadStage.VERIFYING)
        verifiedDescriptor(body, localHash, mimeType, bytes)
    }

    /** Builds the descriptor only when the server hash matches the local one. */
    private fun verifiedDescriptor(body: String?, localHash: String, mimeType: String, bytes: ByteArray): UploadedMedia {
        if (body == null) throw UploadFailure("empty server response")
        // Flat response {status,url,sha256}: strict protocol rules live in the
        // shared core (events); these two string fields are extracted minimally.
        val urlPattern = Regex("\"url\"\\s*:\\s*\"([^\"]{1,2048})\"")
        val hashPattern = Regex("\"(?:sha256|x)\"\\s*:\\s*\"([0-9a-fA-F]{64})\"")
        val url = urlPattern.find(body)?.groupValues?.get(1) ?: throw UploadFailure("no url in response")
        val serverHash = hashPattern.find(body)?.groupValues?.get(1) ?: throw UploadFailure("no hash in response")
        if (!serverHash.equals(localHash, ignoreCase = true)) {
            // Security-visible mismatch: never build a descriptor from this.
            throw UploadFailure("hash mismatch: server returned $serverHash, local $localHash")
        }
        return UploadedMedia(
            url = url,
            sha256Hex = localHash,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
        )
    }

    /**
     * RequestBody that reports cumulative bytes written to OkHttp's sink —
     * the upload stage's REAL fraction (socket bytes, not a timer). Chunked
     * writes keep the callback granularity useful for multi-MB renders.
     * Internal: reused by the BitOS leg of the video route.
     */
    internal class ProgressRequestBody(
        private val bytes: ByteArray,
        private val contentType: MediaType?,
        private val onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?,
    ) : RequestBody() {
        override fun contentType(): MediaType? = contentType
        override fun contentLength(): Long = bytes.size.toLong()

        override fun writeTo(sink: BufferedSink) {
            val reporter = onProgress ?: run {
                sink.write(bytes)
                return
            }
            val total = bytes.size.toLong()
            var written = 0L
            var offset = 0
            val chunk = 64 * 1024
            while (offset < bytes.size) {
                val length = minOf(chunk, bytes.size - offset)
                sink.write(bytes, offset, length)
                offset += length
                written += length
                reporter(written, total)
            }
        }
    }
}
