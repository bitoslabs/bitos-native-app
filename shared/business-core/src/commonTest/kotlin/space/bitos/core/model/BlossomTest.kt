package space.bitos.core.model

import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.publish.NoteComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlossomTest {

    private val author = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
    private val hash = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
    private val composer = NoteComposer(clock = { 1_710_000_000 })

    @Test
    fun composesBlossomUploadAuth() {
        val auth = composer.composeUploadAuth(
            authorPubkey = author,
            serverUrl = "https://cdn.example",
            fileHashHex = hash,
            sizeBytes = 123_456,
            expirationSeconds = 1_710_000_600,
            nowSeconds = 1_710_000_000,
        )!!
        assertEquals(Blossom.AUTH_KIND, auth.kind)
        // BUD-11: the token content MUST be human-readable.
        assertEquals("Upload Blob", auth.content)
        assertEquals(
            listOf(
                listOf("t", "upload"),
                listOf("expiration", "1710000600"),
                listOf("x", hash),
                listOf("size", "123456"),
                listOf("server", "cdn.example"),
            ),
            auth.tags,
        )
        // The auth token is the signed event object `{...}` under Base64url.
        val eventJson = composer.signedEventJson(auth, "aa".repeat(64))!!
        val header = Blossom.authorizationHeaderValue(eventJson)!!
        assertTrue(header.startsWith("Nostr "), header)
        val token = header.removePrefix("Nostr ")
        // Padded Base64url only (servers reject unpadded tokens).
        assertTrue(token.matches(Regex("^[A-Za-z0-9_-]+={0,2}$")), token)
        val decodedJson = Blossom.decodeBase64(token)!!.decodeToString()
        assertEquals(eventJson, decodedJson)
        assertTrue(decodedJson.contains("\"kind\":24242"), decodedJson)
        // Signature attachment: the event parses back through the relay frame.
        val frame = composer.publishMessage(auth, "aa".repeat(64))!!
        val decoded = NostrEventCodec.decodeClientEventFrame(
            Sha256EventHasher, frame, RelayUrl.parse("wss://relay.test"),
        )
        assertEquals(Blossom.AUTH_KIND, decoded.kind)
        assertEquals(auth.tags, decoded.tags)
    }

    @Test
    fun uploadAuthRejectsInvalidInput() {
        assertNull(composer.composeUploadAuth("zz", "https://cdn.example", hash, 100, 1_710_000_600, 1_710_000_000))
        assertNull(composer.composeUploadAuth(author, "http://insecure", hash, 100, 1_710_000_600, 1_710_000_000))
        assertNull(composer.composeUploadAuth(author, "https://cdn.example", "bad", 100, 1_710_000_600, 1_710_000_000))
        assertNull(composer.composeUploadAuth(author, "https://cdn.example", hash, 0, 1_710_000_600, 1_710_000_000))
        // Expired or too-far expirations are refused.
        assertNull(composer.composeUploadAuth(author, "https://cdn.example", hash, 100, 1_709_999_999, 1_710_000_000))
        assertNull(composer.composeUploadAuth(author, "https://cdn.example", hash, 100, 1_710_005_000, 1_710_000_000))
    }

    @Test
    fun composesKind22WithImeta() {
        val media = UploadedMedia(
            url = "https://cdn.example/v.mp4",
            sha256Hex = hash,
            mimeType = "video/mp4",
            sizeBytes = 999_999,
            width = 1080,
            height = 1920,
            durationMs = 4_200,
        )
        val note = composer.composeMediaNote(author, "  my first video  ", media)!!
        assertEquals(NostrKinds.VIDEO, note.kind)
        assertEquals("my first video", note.content)
        assertEquals(
            listOf(
                listOf(
                    "imeta",
                    "url https://cdn.example/v.mp4",
                    "m video/mp4",
                    "x $hash",
                    "size 999999",
                    "dim 1080x1920",
                    "duration 4",
                ),
            ),
            note.tags,
        )
        // Round trip: the published frame parses back with the same media.
        val frame = composer.publishMessage(note, "bb".repeat(64))!!
        val decoded = NostrEventCodec.decodeClientEventFrame(
            Sha256EventHasher, frame, RelayUrl.parse("wss://relay.test"),
        )
        val extracted = MediaMetadata.fromEvent(decoded)!!
        assertEquals("https://cdn.example/v.mp4", extracted.url)
        assertEquals("video/mp4", extracted.mimeType)
        assertEquals(1080, extracted.width)
        assertEquals(1920, extracted.height)
    }

    @Test
    fun uploadedMediaValidatesBounds() {
        assertFails {
            UploadedMedia("http://insecure/v.mp4", hash, "video/mp4", 100)
        }
        assertFails {
            UploadedMedia("https://cdn.example/v.mp4", "short", "video/mp4", 100)
        }
        assertFails {
            UploadedMedia("https://cdn.example/v.mp4", hash, "application/pdf", 100)
        }
        assertFails {
            UploadedMedia("https://cdn.example/v.mp4", hash, "video/mp4", 0)
        }
    }

    @Test
    fun uploadEndpointsJoinUnderBud02() {
        assertEquals("https://cdn.example/upload", Blossom.uploadUrl("https://cdn.example"))
        assertEquals("https://cdn.example/upload", Blossom.uploadUrl("https://cdn.example/"))
        assertEquals("https://cdn.example/upload", Blossom.uploadUrl("https://cdn.example/upload"))
        assertEquals("http://127.0.0.1:3000/upload", Blossom.uploadUrl("http://127.0.0.1:3000"))
    }

    @Test
    fun base64UrlRoundTrip() {
        // RFC 4648 test vector + the UTF-8 boundary cases the token hits (JSON with tags).
        assertEquals("eyJhIjoxfQ==", Blossom.encodeBase64("""{"a":1}""".encodeToByteArray()))
        assertTrue(Blossom.decodeBase64("eyJhIjoxfQ")!!.decodeToString().startsWith("""{"a"""))
        assertEquals("AAA=", Blossom.encodeBase64(byteArrayOf(0, 0)))
        assertTrue(Blossom.decodeBase64("AA")!!.contentEquals(byteArrayOf(0)))
        // Standard alphabet and padding are tolerated on decode.
        assertTrue(Blossom.decodeBase64("AA==")!!.contentEquals(byteArrayOf(0)))
        // Malformed payloads are rejected, not crashed on.
        assertNull(Blossom.decodeBase64("A"))
        assertNull(Blossom.decodeBase64("AB==C"))
        assertNull(Blossom.decodeBase64("!"))
    }

    @Test
    fun challengeParsingAndEncodingRoundTrip() {
        // BUD-11 servers challenge with Base64url JSON.
        val token = Blossom.encodeBase64("""{"tags":[["expiration","1710000600"]]}""".encodeToByteArray())
        assertEquals(1_710_000_600, Blossom.challengeExpiration("Nostr $token"))
        // The legacy percent-encoded form stays parseable.
        val encoded = Blossom.encodeQueryComponent("""{"tags":[["expiration","1710000600"]]}""")
        assertEquals(1_710_000_600, Blossom.challengeExpiration("Nostr $encoded"))
        // '+' decodes as space per query rules.
        assertEquals("{a b}", Blossom.decodeQueryComponent("%7Ba+b%7D"))
        assertNull(Blossom.challengeExpiration("Bearer xyz"))
        assertNull(Blossom.challengeExpiration("Nostr %zz-bad"))
        assertNull(Blossom.challengeExpiration("Nostr !!"))
    }
}
