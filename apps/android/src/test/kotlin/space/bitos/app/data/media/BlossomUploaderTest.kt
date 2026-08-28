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
 * BUD-02 challenge-response flow against a hand-rolled JDK HTTP server:
 * unauthenticated PUT -> 401 challenge -> authed PUT with the signed
 * kind-24242 header -> descriptor built only when the server hash matches.
 */
class BlossomUploaderTest {

    private lateinit var server: HttpServer
    private lateinit var serverUrl: String
    private val authedPuts = AtomicInteger(0)
    private var serveHashMismatch = false
    private var lastAuthHeader: String? = null
    private var lastBody: ByteArray? = null

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverUrl = "http://127.0.0.1:${server.address.port}/upload"
        server.createContext("/upload") { exchange ->
            lastBody = exchange.requestBody.readBytes()
            val authHeader = exchange.requestHeaders.getFirst("Authorization")
            if (authHeader == null) {
                // Challenge with a 10-minute expiration template.
                val challenge = Blossom.encodeQueryComponent("""{"tags":[["expiration","1710000600"]]}""")
                exchange.responseHeaders.add("WWW-Authenticate", "Nostr $challenge")
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
            } else {
                lastAuthHeader = authHeader
                authedPuts.incrementAndGet()
                val hash = Sha256EventHasher.sha256(lastBody!!)
                    .joinToString("") { ((it.toInt() and 0xf0) ushr 4).toString(16) + (it.toInt() and 0x0f).toString(16) }
                val served = if (serveHashMismatch) "00".repeat(32) else hash
                val body = """{"status":"ok","url":"http://127.0.0.1:${server.address.port}/$hash.bin","sha256":"$served"}"""
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
                exchange.close()
            }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private val signer = DeterministicTestSigner("0000000000000000000000000000000000000000000000000000000000000001")

    @Test
    fun challengeResponseUploadVerifiesHash() = runBlocking {
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

        // The authed PUT carried a valid signed kind-24242 with x + size.
        assertEquals(1, authedPuts.get())
        val header = lastAuthHeader!!
        assertTrue(header.startsWith("Nostr "))
        val frame = Blossom.decodeQueryComponent(header.removePrefix("Nostr "))!!
        val decoded = NostrEventCodec.decodeClientEventFrame(
            Sha256EventHasher, frame,
            space.bitos.core.model.RelayUrl.parse("wss://relay.test"),
        )
        assertEquals(Blossom.AUTH_KIND, decoded.kind)
        assertEquals("upload", decoded.tags.first { it.first() == "t" }[1])
        assertEquals(media.sha256Hex, decoded.tags.first { it.first() == "x" }[1])
        assertEquals(bytes.size.toString(), decoded.tags.first { it.first() == "size" }[1])
        // The challenge's expiration was honored.
        assertEquals("1710000600", decoded.tags.first { it.first() == "expiration" }[1])
        // The bytes reached the server intact.
        assertEquals(bytes.toList(), lastBody!!.toList())
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
}
