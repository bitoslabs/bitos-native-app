package space.bitos.core.studio

/**
 * Deterministic destination selection for finished meme video bytes.
 *
 * The BitOS media API is always the canonical event URL. Small videos also
 * require a Blossom replica before publishing; large videos avoid duplicating
 * a costly transfer. Network effects and verification remain native.
 */
object MemeUploadRouting {
    const val DUAL_UPLOAD_MAX_BYTES = 20L * 1024L * 1024L
    const val BITOS_API_MAX_BYTES = 100L * 1024L * 1024L

    enum class Destination { BITOS_API, BLOSSOM }

    fun destinations(mimeType: String, sizeBytes: Long): List<Destination> {
        if (!mimeType.lowercase().startsWith("video/") || sizeBytes !in 1..BITOS_API_MAX_BYTES) {
            return emptyList()
        }
        return if (sizeBytes < DUAL_UPLOAD_MAX_BYTES) {
            listOf(Destination.BITOS_API, Destination.BLOSSOM)
        } else {
            listOf(Destination.BITOS_API)
        }
    }
}
