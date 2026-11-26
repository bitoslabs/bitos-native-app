package space.bitos.core.identity.nip46

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import space.bitos.core.identity.SignEventRequest
import space.bitos.core.identity.SignEventResult
import space.bitos.core.identity.appendTagsJson
import space.bitos.core.identity.escapeJson

/**
 * JSON codec for the NIP-46 reducer surface (SBC-008b): the iOS bridge is
 * String-only, so session state, commands and outcomes cross as a
 * versioned wire. Android uses the typed reducer directly; both drive the
 * SAME tested state machine. No secrets or payloads appear in outcome
 * state beyond the signed event the user explicitly approved.
 */
object Nip46SessionCodec {

    const val WIRE_VERSION = 1

    // ── State ────────────────────────────────────────────────────────

    fun encodeState(state: Nip46SessionState): String = buildJsonObject {
        put("v", WIRE_VERSION)
        state.subscriptionId?.let { put("sub", it) }
        state.rpc?.let { rpc ->
            put(
                "rpc",
                buildJsonObject {
                    put("id", rpc.requestId)
                    put("method", rpc.method)
                    put("deadline", rpc.deadlineMs)
                    rpc.expectedSignerPubkeyHex?.let { put("signer", it) }
                    rpc.originating?.let { put("origin", encodeRequestJson(it)) }
                },
            )
        }
        state.finished?.let { put("finished", encodeOutcomeJson(it)) }
        if (state.awaitingAuthUrl) put("authUrl", true)
    }.toString()

    fun decodeState(json: String): Nip46SessionState? {
        if (json.isEmpty() || json.length > 8_192) return null
        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            if (root["v"]?.jsonPrimitive?.longOrNull != WIRE_VERSION.toLong()) return null
            val rpc = root["rpc"]?.jsonObject?.let {
                Nip46Rpc(
                    requestId = it["id"]?.jsonPrimitive?.contentOrNull ?: return null,
                    method = it["method"]?.jsonPrimitive?.contentOrNull ?: return null,
                    deadlineMs = it["deadline"]?.jsonPrimitive?.longOrNull ?: 0L,
                    expectedSignerPubkeyHex = it["signer"]?.jsonPrimitive?.contentOrNull,
                    originating = it["origin"]?.jsonPrimitive?.contentOrNull?.let(::decodeRequestJson),
                )
            }
            Nip46SessionState(
                subscriptionId = root["sub"]?.jsonPrimitive?.contentOrNull,
                rpc = rpc,
                finished = root["finished"]?.jsonObject?.let(::decodeOutcomeJson),
                awaitingAuthUrl = root["authUrl"]?.jsonPrimitive?.contentOrNull == "true",
            )
        } catch (_: Exception) {
            null
        }
    }

    // ── SignEventRequest (originating payload for sign_event) ────────

    fun encodeRequestJson(request: SignEventRequest): String =
        space.bitos.core.identity.SignEventRequestJson.encode(request)

    fun decodeRequestJson(json: String): SignEventRequest? =
        space.bitos.core.identity.SignEventRequestJson.decode(json)

    // ── Commands ─────────────────────────────────────────────────────

    fun encodeCommands(commands: List<Nip46Command>): String = buildJsonArray {
        commands.forEach { command ->
            add(
                when (command) {
                    is Nip46Command.OpenResponseLane -> buildJsonObject {
                        put("type", "open_lane"); put("frame", command.requestFrame)
                    }
                    is Nip46Command.CloseResponseLane -> buildJsonObject {
                        put("type", "close_lane"); put("frame", command.closeFrame)
                    }
                    is Nip46Command.SendRequest -> buildJsonObject {
                        put("type", "send_request")
                        put("event", command.unsignedEvent.json)
                        put("id", command.unsignedEvent.idHex)
                    }
                    is Nip46Command.SendPlainResponse -> buildJsonObject {
                        put("type", "send_response"); put("envelope", command.envelopeJson)
                    }
                    is Nip46Command.TeardownSockets -> buildJsonObject { put("type", "teardown") }
                },
            )
        }
    }.toString()

    // ── Outcomes ─────────────────────────────────────────────────────

    fun encodeOutcomeJson(outcome: Nip46Outcome): kotlinx.serialization.json.JsonObject = when (outcome) {
        is Nip46Outcome.ConnectAcknowledged -> buildJsonObject {
            put("type", "connect_ack")
            outcome.echoedSecret?.let { put("secret", it) }
            outcome.signerPubkeyHex?.let { put("signer", it) }
        }
        is Nip46Outcome.PublicKeyObtained -> buildJsonObject {
            put("type", "public_key"); put("pubkey", outcome.accountPubkeyHex)
        }
        is Nip46Outcome.EventApproved -> buildJsonObject {
            put("type", "approved")
            put("event", signedEventJson(outcome.event))
            put("id", outcome.event.idHex)
            put("sig", outcome.event.signatureHex)
        }
        is Nip46Outcome.Failed -> buildJsonObject {
            put("type", "failed")
            put("code", outcome.result.outcomeCode)
            val reason = when (val result = outcome.result) {
                is SignEventResult.Unavailable -> result.reasonCode
                is SignEventResult.Rejected -> result.reasonCode
                is SignEventResult.Invalid -> result.reasonCode
                else -> null
            }
            reason?.let { put("reason", it) }
        }
    }

    fun encodeOutcome(outcome: Nip46Outcome): String = encodeOutcomeJson(outcome).toString()

    fun decodeOutcomeJson(root: kotlinx.serialization.json.JsonObject): Nip46Outcome? {
        return when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "connect_ack" -> Nip46Outcome.ConnectAcknowledged(
                echoedSecret = root["secret"]?.jsonPrimitive?.contentOrNull,
                signerPubkeyHex = root["signer"]?.jsonPrimitive?.contentOrNull,
            )
            "public_key" -> Nip46Outcome.PublicKeyObtained(
                root["pubkey"]?.jsonPrimitive?.contentOrNull ?: return null,
            )
            "approved" -> space.bitos.core.identity.SignedEventValidator.parseSignedEvent(
                root["event"]?.jsonPrimitive?.contentOrNull ?: return null,
            )?.let { Nip46Outcome.EventApproved(it) }
            "failed" -> Nip46Outcome.Failed(
                when (root["code"]?.jsonPrimitive?.contentOrNull) {
                    SignEventResult.Denied.CODE -> SignEventResult.Denied(
                        root["reason"]?.jsonPrimitive?.contentOrNull,
                    )
                    SignEventResult.TimedOut.CODE -> SignEventResult.TimedOut(
                        root["reason"]?.jsonPrimitive?.contentOrNull,
                    )
                    SignEventResult.Unavailable.CODE -> SignEventResult.Unavailable(
                        root["reason"]?.jsonPrimitive?.contentOrNull ?: "unavailable",
                    )
                    SignEventResult.Rejected.CODE -> SignEventResult.Rejected(
                        root["reason"]?.jsonPrimitive?.contentOrNull ?: "rejected",
                    )
                    SignEventResult.Cancelled.outcomeCode -> SignEventResult.Cancelled
                    SignEventResult.Invalid.CODE -> SignEventResult.Invalid(
                        root["reason"]?.jsonPrimitive?.contentOrNull ?: "invalid",
                    )
                    else -> return null
                },
            )
            else -> null
        }
    }

    private fun signedEventJson(event: space.bitos.core.identity.SignedEvent): String =
        buildString(160 + event.content.length) {
            append("{\"id\":\"").append(event.idHex)
                .append("\",\"pubkey\":\"").append(event.pubkeyHex)
                .append("\",\"created_at\":").append(event.createdAtSeconds)
                .append(",\"kind\":").append(event.kind).append(",\"tags\":")
            appendTagsJson(this, event.tags)
            append(",\"content\":\"").append(escapeJson(event.content))
                .append("\",\"sig\":\"").append(event.signatureHex).append("\"}")
        }
}
