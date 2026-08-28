package space.bitos.app.data.relay

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import space.bitos.core.model.RelayUrl
import java.util.concurrent.TimeUnit
import kotlin.random.Random

enum class RelayConnectionState { CONNECTING, CONNECTED, DISCONNECTED, RETRYING }

/** One relay frame received on an open socket. */
data class RelayFrame(val relay: RelayUrl, val message: String)

/**
 * Platform transport port for one relay websocket. The OkHttp implementation
 * is production; tests inject a fake so repository rules stay verifiable
 * without a network.
 */
interface RelayTransport {
    val state: StateFlow<RelayConnectionState>
    val frames: SharedFlow<RelayFrame>

    fun connect()
    fun send(message: String): Boolean
    fun close(code: Int = 1000, reason: String = "client close")
}

/**
 * OkHttp websocket transport. Reconnect with capped exponential backoff and
 * jitter is owned by [RelayPool] so the policy stays testable in one place.
 */
class OkHttpRelayTransport(
    private val relay: RelayUrl,
    private val client: OkHttpClient,
    private val onClosed: () -> Unit,
) : RelayTransport, WebSocketListener() {

    private val mutableState = MutableStateFlow(RelayConnectionState.DISCONNECTED)
    private val mutableFrames = MutableSharedFlow<RelayFrame>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val state: StateFlow<RelayConnectionState> = mutableState
    override val frames: SharedFlow<RelayFrame> = mutableFrames

    private var socket: WebSocket? = null

    override fun connect() {
        if (mutableState.value == RelayConnectionState.CONNECTED ||
            mutableState.value == RelayConnectionState.CONNECTING
        ) return
        mutableState.value = RelayConnectionState.CONNECTING
        val request = Request.Builder().url(relay.value).build()
        socket = client.newWebSocket(request, this)
    }

    override fun send(message: String): Boolean = socket?.send(message) ?: false

    override fun close(code: Int, reason: String) {
        socket?.close(code, reason)
        socket = null
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        mutableState.value = RelayConnectionState.CONNECTED
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        mutableFrames.tryEmit(RelayFrame(relay, text))
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        mutableState.value = RelayConnectionState.DISCONNECTED
        onClosed()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        mutableState.value = RelayConnectionState.DISCONNECTED
        onClosed()
    }
}

fun relayHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .pingInterval(25, TimeUnit.SECONDS)
    .build()

/** Capped exponential backoff with full jitter (REL-001 reconnect policy). */
fun reconnectDelayMillis(attempt: Int, random: Random = Random.Default): Long {
    val capped = 1_000L shl attempt.coerceAtMost(5)
    return random.nextLong(capped + 1)
}
