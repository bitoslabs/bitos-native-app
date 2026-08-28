package space.bitos.app.data.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import space.bitos.core.identity.IdentitySigner
import space.bitos.core.model.Blossom
import space.bitos.core.model.UploadedMedia
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.publish.NoteComposer
import java.util.concurrent.TimeUnit

/**
 * Blossom (BUD-02) uploader: hash → challenge → signed kind-24242 auth →
 * PUT bytes → verify the returned hash matches the local one. Hash mismatch
 * is security-visible and blocking (PUB-002/006): the pipeline refuses to
 * build a descriptor from an unverified upload.
 */
class BlossomUploader(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {

    class UploadFailure(reason: String) : Exception(reason)

    /**
     * Uploads [bytes] and returns the verified descriptor. The SHA-256 is
     * computed locally first and checked against the server's response.
     */
    suspend fun upload(
        bytes: ByteArray,
        mimeType: String,
        signer: IdentitySigner,
        serverUrl: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): UploadedMedia = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size > Blossom.MAX_FILE_BYTES) throw UploadFailure("file out of bounds")
        val localHash = Sha256EventHasher.sha256(bytes)
            .joinToString("") { ((it.toInt() and 0xf0) ushr 4).toString(16) + (it.toInt() and 0x0f).toString(16) }

        // 1. Unauthenticated PUT -> expect 401 with the Nostr challenge.
        val firstCall = http.newCall(
            Request.Builder()
                .url(serverUrl)
                .put(bytes.toRequestBody(mimeType.toMediaType()))
                .build(),
        )
        val challengeHeader = firstCall.execute().use { response ->
            if (response.code == 200) {
                // Some servers accept the first PUT; verify and return.
                val body = response.body?.string()
                return@withContext verifiedDescriptor(body, localHash, mimeType, bytes)
            }
            if (response.code != 401) throw UploadFailure("server rejected upload: ${response.code}")
            response.header("WWW-Authenticate") ?: throw UploadFailure("server sent no auth challenge")
        }

        // 2. Compose + sign the kind-24242 auth event with the challenge's
        //    expiration (fallback now+600s).
        val expiration = Blossom.challengeExpiration(challengeHeader) ?: (nowSeconds + 600)
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
        val authHeader = "Nostr " + Blossom.encodeQueryComponent(
            composer.publishMessage(auth, signature) ?: throw UploadFailure("auth frame rejected"),
        )

        // 3. Authenticated PUT.
        val body = http.newCall(
            Request.Builder()
                .url(serverUrl)
                .header("Authorization", authHeader)
                .put(bytes.toRequestBody(mimeType.toMediaType()))
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) throw UploadFailure("upload failed: ${response.code}")
            response.body?.string()?.takeIf { it.length <= 65_536 } ?: throw UploadFailure("empty server response")
        }

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
}
