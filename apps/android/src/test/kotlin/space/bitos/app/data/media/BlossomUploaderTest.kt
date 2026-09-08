package space.bitos.app.data.media

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import space.bitos.core.identity.DeterministicTestSigner
import space.bitos.core.model.Blossom
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.nostr.NostrEventCodec
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * BUD-02/11 upload flow against a hand-rolled JDK HTTP server: signed
 * kind-24242 Base64url `Authorization: Nostr` header on `PUT /upload` ->
 * descriptor built only when the server hash matches.
 */
class BlossomUploaderTest {

    private lateinit var server: HttpServer
    private lateinit var serverUrl: String
    private val authedPuts = AtomicInteger(0)
    private var serveHashMismatch = false
    private var serveCreated = false
    private var lastAuthHeader: String? = null
    private var lastBody: ByteArray? = null
    private var lastShaHeader: String? = null

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverUrl = "http://127.0.0.1:${server.address.port}"
        server.createContext("/upload") { exchange ->
            lastBody = exchange.requestBody.readBytes()
            val authHeader = exchange.requestHeaders.getFirst("Authorization")
            if (authHeader == null || !authHeader.startsWith("Nostr ")) {
                // Production servers reject the unauthenticated probe with
                // 400 + X-Reason, not the legacy 401 challenge.
                exchange.responseHeaders.add("X-Reason", "missing auth event")
                exchange.sendResponseHeaders(400, -1)
                exchange.close()
                return@createContext
            }
            // The root endpoint is gone in BUD-02; uploads live under /upload.
            authedPuts.incrementAndGet()
            lastAuthHeader = authHeader
            lastShaHeader = exchange.requestHeaders.getFirst("X-SHA-256")
            val hash = Sha256EventHasher.sha256(lastBody!!)
                .joinToString("") { ((it.toInt() and 0xf0) ushr 4).toString(16) + (it.toInt() and 0x0f).toString(16) }
            val served = if (serveHashMismatch) "00".repeat(32) else hash
            val body = """{"status":"ok","url":"http://127.0.0.1:${server.address.port}/$hash.bin","sha256":"$served"}"""
            // BUD-02: 201 when newly stored, 200 when it already existed.
            exchange.sendResponseHeaders(if (serveCreated) 201 else 200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private val signer = DeterministicTestSigner("0000000000000000000000000000000000000000000000000000000000000001")

    @Test
    fun signedUploadVerifiesHash() = runBlocking {
        val bytes = "bitos-test-media-bytes".toByteArray()
        val media = BlossomUploader().upload(
            bytes = bytes,
            mimeType = "video/mp4",
            signer = signer,
            serverUrl = serverUrl,
            nowSeconds = 1_710_000_000,
        )

        assertEquals("video/mp4", media.mimeType)
        assertEquals(bytes.size.toLong(), media.sizeBytes)
        assertTrue(media.url.endsWith("${media.sha256Hex}.bin"))

        // Exactly one request: BUD-02 uploads sign upfront, no probing.
        assertEquals(1, authedPuts.get())
        assertEquals("${media.sha256Hex}", lastShaHeader)
        val header = lastAuthHeader!!
        assertTrue(header.startsWith("Nostr "))
        // BUD-11: the token is Base64url of the signed event object.
        val token = header.removePrefix("Nostr ")
        assertTrue(token.matches(Regex("^[A-Za-z0-9_-]+={0,2}$")), token)
        val eventJson = Blossom.decodeBase64(token)!!.decodeToString()
        assertTrue(eventJson.startsWith("{"), eventJson)
        val decoded = NostrEventCodec.decodeEventObject(
            Sha256EventHasher, eventJson,
            space.bitos.core.model.RelayUrl.parse("wss://relay.test"),
        )
        assertEquals(Blossom.AUTH_KIND, decoded.kind)
        assertEquals("upload", decoded.tags.first { it.first() == "t" }[1])
        assertEquals(media.sha256Hex, decoded.tags.first { it.first() == "x" }[1])
        assertEquals(bytes.size.toString(), decoded.tags.first { it.first() == "size" }[1])
        // The token is scoped to the target server host (BUD-11).
        assertEquals("127.0.0.1:${server.address.port}", decoded.tags.first { it.first() == "server" }[1])
        // The fallback expiration (now + 10 min) was used: servers that skip
        // the 401 challenge still authorize.
        assertEquals("1710000600", decoded.tags.first { it.first() == "expiration" }[1])
        // The bytes reached the server intact.
        assertEquals(bytes.toList(), lastBody!!.toList())
    }

    @Test
    fun created201IsAccepted() = runBlocking {
        serveCreated = true
        val media = BlossomUploader().upload(
            bytes = "fresh-blob".toByteArray(),
            mimeType = "image/jpeg",
            signer = signer,
            serverUrl = serverUrl,
            nowSeconds = 1_710_000_000,
        )
        assertTrue(media.url.startsWith("http://127.0.0.1:"))
    }

    @Test
    fun hashMismatchIsBlocking() = runBlocking {
        serveHashMismatch = true
        val bytes = "some-media".toByteArray()
        val failure = assertFailsWith<BlossomUploader.UploadFailure> {
            BlossomUploader().upload(bytes, "video/mp4", signer, serverUrl, nowSeconds = 1_710_000_000)
        }
        assertTrue(failure.message!!.contains("hash mismatch"), failure.message)
    }

    @Test
    fun uploadProgressReportsRealBytes() = runBlocking {
        val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        val samples = mutableListOf<Pair<Long, Long>>()
        val media = BlossomUploader().upload(
            bytes, "video/mp4", signer, serverUrl,
            nowSeconds = 1_710_000_000,
        ) { written, total -> samples += written to total }

        // The fraction is socket truth: monotonic, total is the payload,
        // and the last sample completes it.
        assertTrue(samples.isNotEmpty())
        assertEquals(bytes.size.toLong(), samples.first().second)
        assertEquals(bytes.size.toLong(), samples.last().first)
        assertEquals(bytes.size.toLong(), samples.last().second)
        samples.zipWithNext().forEach { (earlier, later) ->
            assertTrue(later.first >= earlier.first, "$earlier -> $later")
        }
        assertEquals(bytes.toList(), lastBody!!.toList())
        assertEquals(bytes.size.toLong(), media.sizeBytes)
    }
}
