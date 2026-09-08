package space.bitos.core.publish

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * APP-008 GIF picker rules (legacy `GifPickerSheet` / web `GifPicker.svelte`
 * parity): request building incl. query encoding, lenient Giphy parse with
 * the preview-fallback chain, pagination math, recent merge and the
 * versioned cache wire.
 */
class GifPickerTest {

    private fun giphyBody(
        vararg entries: Triple<String, String, String>, // id → original url → small url
        offset: Int = 0,
        count: Int = entries.size,
        totalCount: Int? = null,
    ): String {
        val data = entries.joinToString(prefix = "[", separator = ",", postfix = "]") { (id, original, small) ->
            """
            {"id":"$id","images":{
              "original":{"url":"$original","width":"480","height":"480"},
              "fixed_height":{"url":"$original","width":"200","height":"200"},
              "fixed_height_small":{"url":"$small","width":"100","height":"100"}
            }}
            """.trimIndent()
        }
        val pagination = buildString {
            append("\"pagination\":{\"offset\":$offset,\"count\":$count")
            if (totalCount != null) append(",\"total_count\":$totalCount")
            append("}")
        }
        return "{\"data\":$data,$pagination}"
    }

    @Test
    fun buildsTrendingAndSearchUrls() {
        val trending = GifPickerContract.buildUrl(GifPickerContract.DEFAULT_API_KEY, "  ", 0)
        assertEquals(
            "https://api.giphy.com/v1/gifs/trending?api_key=${GifPickerContract.DEFAULT_API_KEY}&limit=30&offset=0&rating=pg",
            trending,
        )
        val search = GifPickerContract.buildUrl("KEY", "bitcoin meme", 30)
        assertTrue(search.startsWith("https://api.giphy.com/v1/gifs/search?api_key=KEY&q="))
        assertTrue(search.contains("bitcoin%20meme"))
        assertTrue(search.endsWith("&limit=30&offset=30&rating=pg"))
    }

    @Test
    fun encodesQueryComponentPerRfc3986() {
        assertEquals("abcXYZ0189-_.~", GifPickerContract.encodeQueryComponent("abcXYZ0189-_.~"))
        assertEquals("%20", GifPickerContract.encodeQueryComponent(" "))
        assertEquals("%2B", GifPickerContract.encodeQueryComponent("+"))
        assertEquals("%F0%9F%94%A5", GifPickerContract.encodeQueryComponent("🔥"))
    }

    @Test
    fun parsesPreviewFallbackChain() {
        val full = giphyBody(
            Triple("a", "https://media.giphy.com/media/a/giphy.gif", "https://media.giphy.com/media/a/100.gif"),
        )
        val choices = GifPickerContract.parseChoices(full)
        assertEquals(1, choices.size)
        assertEquals("a", choices[0].id)
        assertEquals("https://media.giphy.com/media/a/giphy.gif", choices[0].url)
        assertEquals("https://media.giphy.com/media/a/100.gif", choices[0].preview)
        assertEquals(100, choices[0].width)
        assertEquals(100, choices[0].height)
    }

    @Test
    fun parseDropsMalformedEntriesWithoutThrowing() {
        val hostile = """
            {"data":[
              {"id":"ok","images":{"original":{"url":"https://a.example/full.gif"},"fixed_height_small":{"url":"https://a.example/small.gif","width":"100","height":"bad"}}},
              {"id":"no-images","images":{}},
              {"id":"null-url","images":{"original":{"url":null},"fixed_height_small":{"url":"https://a.example/s.gif"}}},
              "not-an-object",
              {"id":"","images":{"original":{"url":"https://a.example/x.gif"},"fixed_height_small":{"url":"https://a.example/xs.gif"}}}
            ]}
        """.trimIndent()
        val choices = GifPickerContract.parseChoices(hostile)
        // "null-url" survives: original is null → full falls back to the
        // small preview (legacy parity).
        assertEquals(listOf("ok", "null-url", ""), choices.map { it.id })
        assertEquals("https://a.example/s.gif", choices[1].url)
        assertEquals(120, choices[0].height) // unparseable dim falls back
        assertTrue(GifPickerContract.parseChoices("not json {").isEmpty())
        assertTrue(GifPickerContract.parseChoices("{\"data\":\"nope\"}").isEmpty())
    }

    @Test
    fun paginationUsesReturnedOffsetAndTotal() {
        val body = giphyBody(offset = 30, count = 30, totalCount = 95)
        val page = GifPickerContract.pagination(body, fetchedCount = 30, requestedOffset = 30)
        assertEquals(60, page.nextOffset)
        assertTrue(page.hasMore)
        val last = giphyBody(offset = 90, count = 5, totalCount = 95)
        assertFalse(GifPickerContract.pagination(last, 5, 90).hasMore)
        // No pagination node: full page implies more.
        val bare = GifPickerContract.pagination("{\"data\":[]}", 30, 0)
        assertEquals(30, bare.nextOffset)
        assertTrue(bare.hasMore)
    }

    @Test
    fun recentMergeDedupsNewestFirstAndCaps() {
        fun gif(id: String) = GifChoice(id, "https://a.example/$id.gif", "https://a.example/$id-s.gif", 100, 100)
        val existing = (1..GifPickerContract.RECENT_LIMIT).map { gif("$it") }
        val merged = GifPickerContract.mergeRecent(existing, gif("7"))
        assertEquals(GifPickerContract.RECENT_LIMIT, merged.size)
        assertEquals("7", merged.first().id)
        assertEquals(1, merged.count { it.id == "7" })
        // Picks without an id never collapse distinct entries.
        val anon = GifPickerContract.mergeRecent(listOf(gif("")), gif(""))
        assertEquals(2, anon.size)
    }

    @Test
    fun cacheWireRoundTripsAndRejectsCorruption() {
        val recent = listOf(GifChoice("r", "https://a.example/r.gif", "https://a.example/rs.gif", 100, 100))
        val trending = listOf(GifChoice("t", "https://a.example/t.gif", "https://a.example/ts.gif", 100, 100))
        val wire = GifPickerContract.cacheEncode(recent, trending, savedAtMs = 1_000)
        val decoded = GifPickerContract.cacheDecode(wire)!!
        assertEquals(recent, decoded.recent)
        assertEquals(trending, decoded.trending)
        assertEquals(1_000L, decoded.savedAtMs)
        assertNull(GifPickerContract.cacheDecode("{corrupt"))
        // Unknown/future wire versions reject (v2 = the current stickers
        // cache wire; a hypothetical v3 must not half-decode).
        assertNull(GifPickerContract.cacheDecode("{\"v\":3,\"recent\":[]}"))
        assertNull(GifPickerContract.cacheDecode("x".repeat(GifPickerContract.MAX_WIRE_LENGTH + 1)))
        // Hostile oversized lists clamp instead of failing the decode.
        val hostileWire = GifPickerContract.cacheEncode(
            recent = (1..40).map { GifChoice("$it", "https://a.example/$it.gif", "https://a.example/$it-s.gif", 100, 100) },
            trending = (1..40).map { GifChoice("t$it", "https://a.example/t$it.gif", "https://a.example/t$it-s.gif", 100, 100) },
            savedAtMs = 1_000,
        )
        val clamped = GifPickerContract.cacheDecode(hostileWire)!!
        assertEquals(GifPickerContract.RECENT_LIMIT, clamped.recent.size)
        assertEquals(GifPickerContract.MAX_CACHED_TRENDING, clamped.trending.size)
    }

    @Test
    fun cacheFreshnessFollowsTtlWindow() {
        val saved = 10_000L
        assertTrue(GifPickerContract.isCacheFresh(saved, saved))
        assertTrue(GifPickerContract.isCacheFresh(saved, saved + GifPickerContract.CACHE_TTL_MS))
        assertFalse(GifPickerContract.isCacheFresh(saved, saved + GifPickerContract.CACHE_TTL_MS + 1))
        assertFalse(GifPickerContract.isCacheFresh(saved, saved - 1)) // clock skew = stale
    }

    @Test
    fun choicesJsonWireRoundTrips() {
        val choices = listOf(
            GifChoice("a", "https://a.example/a.gif", "https://a.example/as.gif", 100, 200),
            GifChoice("b", "https://a.example/b.gif", "https://a.example/bs.gif", 50, 50),
        )
        assertEquals(choices, GifPickerContract.choicesFromJson(GifPickerContract.choicesToJson(choices)))
        assertTrue(GifPickerContract.choicesFromJson("[{\"url\":\"ftp://nope\"}]").isEmpty())
    }

    @Test
    fun stickersKindHitsTheTransparentCutOutEndpointsAndCachesPerKind() {
        // Web parity: stickers = Giphy's transparent cut-out endpoints.
        assertEquals(
            "https://api.giphy.com/v1/stickers/trending?api_key=KEY&limit=30&offset=0&rating=pg",
            GifPickerContract.buildUrl("KEY", " ", 0, stickers = true),
        )
        assertEquals(
            "https://api.giphy.com/v1/stickers/search?api_key=KEY&q=gm&limit=30&offset=30&rating=pg",
            GifPickerContract.buildUrl("KEY", "gm", 30, stickers = true),
        )
        // v2 cache: per-kind trending pages + the remembered tab.
        val gifs = listOf(GifChoice("g1", "https://a/g1.gif", "https://a/g1p.gif", 100, 100))
        val stickers = listOf(GifChoice("s1", "https://a/s1.gif", "https://a/s1p.gif", 80, 80))
        val encoded = GifPickerContract.cacheEncode(
            gifs, gifs, savedAtMs = 1_000,
            stickersTrending = stickers, stickersSavedAtMs = 2_000, stickersKind = true,
        )
        val decoded = GifPickerContract.cacheDecode(encoded)!!
        assertEquals(gifs, decoded.trending)
        assertEquals(stickers, decoded.stickersTrending)
        assertEquals(2_000L, decoded.stickersSavedAtMs)
        assertTrue(decoded.stickersKind)
        // v1 wires (no stickers fields) still decode with GIF defaults.
        val v1 = GifPickerContract.cacheEncode(gifs, gifs, 1_000)
            .replace("\"v\":2", "\"v\":1")
            .substringBefore(",\"stickersTrending\"") + "}"
        val legacy = GifPickerContract.cacheDecode(v1)
        assertEquals(gifs, legacy?.trending)
        assertEquals(emptyList<GifChoice>(), legacy?.stickersTrending)
        assertTrue(legacy?.stickersKind == false)
    }
}
