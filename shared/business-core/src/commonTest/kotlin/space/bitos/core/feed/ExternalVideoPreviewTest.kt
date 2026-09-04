package space.bitos.core.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalVideoPreviewTest {
    @Test
    fun recognizesYouTubeWatchShortAndEmbedUrls() {
        val urls = listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?t=4",
            "https://youtube.com/shorts/dQw4w9WgXcQ",
        )
        urls.forEach { url ->
            val preview = requireNotNull(ExternalVideoPreviews.fromUrl(url))
            assertEquals("YouTube", preview.providerName)
            assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", preview.thumbnailUrl)
        }
    }

    @Test
    fun rejectsNonProviderAndMalformedUrls() {
        assertNull(ExternalVideoPreviews.fromUrl("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(ExternalVideoPreviews.fromUrl("https://youtu.be/not-an-id"))
    }

    @Test
    fun ignoresCaptionPunctuationAfterAProviderUrl() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ",
            ExternalVideoPreviews.fromUrl("https://youtu.be/dQw4w9WgXcQ.")?.url,
        )
    }
}
