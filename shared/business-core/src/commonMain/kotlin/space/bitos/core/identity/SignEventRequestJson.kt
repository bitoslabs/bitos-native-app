package space.bitos.core.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import space.bitos.core.model.NostrLimits

/**
 * Serializable wire for [SignEventRequest] (SBC-008a): used for durable
 * pending-signer state across process death and for cross-boundary origin
 * tracking. Public fields only — nothing secret rides a request.
 */
object SignEventRequestJson {

    fun encode(request: SignEventRequest): String = buildJsonObject {
        put("requestId", request.requestId)
        put("accountPubkeyHex", request.accountPubkeyHex)
        put("createdAtSeconds", request.createdAtSeconds)
        put("kind", request.kind)
        put("tags", buildJsonArray {
            request.tags.forEach { tag ->
                add(buildJsonArray { tag.forEach { item -> add(kotlinx.serialization.json.JsonPrimitive(item)) } })
            }
        })
        put("content", request.content)
    }.toString()

    fun decode(json: String): SignEventRequest? {
        if (json.isEmpty() || json.length > NostrLimits.MAX_EVENT_BYTES) return null
        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            SignEventRequest(
                requestId = root["requestId"]?.jsonPrimitive?.contentOrNull ?: return null,
                accountPubkeyHex = root["accountPubkeyHex"]?.jsonPrimitive?.contentOrNull ?: return null,
                createdAtSeconds = root["createdAtSeconds"]?.jsonPrimitive?.longOrNull ?: return null,
                kind = root["kind"]?.jsonPrimitive?.longOrNull?.toInt() ?: return null,
                tags = root["tags"]?.jsonArray?.map { tag ->
                    tag.jsonArray.map { it.jsonPrimitive.contentOrNull ?: return null }
                } ?: return null,
                content = root["content"]?.jsonPrimitive?.contentOrNull ?: return null,
            )
        } catch (_: Exception) {
            null
        }
    }
}
