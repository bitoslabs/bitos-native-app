package space.bitos.core.studio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON wire for [MemeCommand] (plan MST-005): lets the Swift bridge apply
 * editor commands without leaking Kotlin types — one op per call, hostile
 * values clamped by [MemeRules.apply]. Unknown ops decode to null.
 *
 * `{"op":"add","overlay":{…}} · {"op":"remove","id":…} ·
 *  {"op":"reorder","id":…,"index":…} · {"op":"update","id":…,"x":…,…} ·
 *  {"op":"trim","start":…,"end":…} · {"op":"delay","ms":…}`
 */
object MemeCommandCodec {

    private val lenientJson = Json { ignoreUnknownKeys = true }

    fun encode(command: MemeCommand): String = buildJsonObject {
        when (command) {
            is MemeCommand.AddOverlay -> {
                put("op", "add")
                put("overlay", MemeProjectContract.overlayJson(command.overlay))
            }

            is MemeCommand.RemoveOverlay -> {
                put("op", "remove")
                put("id", command.id)
            }

            is MemeCommand.ReorderOverlay -> {
                put("op", "reorder")
                put("id", command.id)
                put("index", command.toIndex)
            }

            is MemeCommand.UpdateOverlay -> {
                put("op", "update")
                put("id", command.id)
                command.x?.let { put("x", it) }
                command.y?.let { put("y", it) }
                command.scale?.let { put("scale", it) }
                command.rotationDeg?.let { put("rot", it) }
                command.text?.let { put("text", it) }
                command.font?.let { put("font", it.name.lowercase()) }
                command.size?.let { put("size", it) }
                command.colorIndex?.let { put("color", it) }
                command.outline?.let { put("outline", it) }
                command.shadow?.let { put("shadow", it) }
                command.fx?.let { put("fx", it.name.lowercase()) }
                if (command.clearFx) put("clearFx", true)
                command.startMs?.let { put("startMs", it) }
                command.endMs?.let { put("endMs", it) }
            }

            is MemeCommand.SetTrim -> {
                put("op", "trim")
                put("start", command.startMs)
                put("end", command.endMs)
            }

            is MemeCommand.SetSpeed -> {
                put("op", "speed")
                put("rate", command.rate)
            }

            is MemeCommand.SetFrameDelay -> {
                put("op", "delay")
                put("ms", command.delayMs)
            }

            is MemeCommand.SetLook -> {
                put("op", "look")
                put("look", command.lookId)
            }

            is MemeCommand.SetAdjust -> {
                put("op", "adjust")
                put("bri", command.adjust.brightness)
                put("con", command.adjust.contrast)
                put("sat", command.adjust.saturation)
            }

            is MemeCommand.AddSfxCue -> {
                put("op", "cue-add")
                put("cue", buildJsonObject {
                    put("id", command.cue.id)
                    put("sfx", command.cue.sfx)
                    put("at", command.cue.atMs)
                    put("g", command.cue.gain)
                })
            }

            is MemeCommand.RemoveSfxCue -> {
                put("op", "cue-del")
                put("id", command.id)
            }

            is MemeCommand.SetSoundtrack -> {
                val sound = MemeSoundRules.normalize(command.soundtrack)
                if (sound == null) {
                    put("op", "sound-del")
                } else {
                    put("op", "sound")
                    if (sound.url.isNotBlank()) put("url", sound.url)
                    put("sha256", sound.sha256)
                    put("ms", sound.durationMs)
                    if (sound.startMs > 0) put("start", sound.startMs)
                    if (sound.volume != 1f) put("vol", sound.volume)
                    if (sound.offsetMs > 0) put("offset", sound.offsetMs)
                    sound.sourceNoteId?.let { put("src", it) }
                    sound.sourceAuthorPubkey?.let { put("author", it) }
                    if (sound.label.isNotBlank()) put("label", sound.label)
                    if (sound.loop) put("loop", true)
                }
            }

            is MemeCommand.AddStroke -> {
                put("op", "stroke-add")
                put("stroke", buildJsonObject {
                    put("id", command.stroke.id)
                    put("c", command.stroke.colorIndex)
                    put("w", command.stroke.widthNorm)
                    put("p", buildJsonArray {
                        command.stroke.points.forEach { point -> add(JsonPrimitive(point)) }
                    })
                })
            }

            is MemeCommand.RemoveStroke -> {
                put("op", "stroke-del")
                put("id", command.id)
            }

            is MemeCommand.ClearDrawing -> put("op", "draw-clear")
        }
    }.toString()

    fun decode(json: String): MemeCommand? {
        if (json.length > 2_048) return null
        return try {
            val obj = lenientJson.parseToJsonElement(json).jsonObject
            when (obj["op"]?.jsonPrimitive?.content) {
                "add" -> obj["overlay"]?.jsonObject
                    ?.let(MemeProjectContract::decodeOverlayJson)
                    ?.let(MemeCommand::AddOverlay)

                "remove" -> obj["id"]?.jsonPrimitive?.content
                    ?.let(MemeCommand::RemoveOverlay)

                "reorder" -> obj["id"]?.jsonPrimitive?.content?.let { id ->
                    intOf(obj, "index")?.let { index ->
                        MemeCommand.ReorderOverlay(id, index)
                    }
                }

                "update" -> obj["id"]?.jsonPrimitive?.content?.let { id ->
                    MemeCommand.UpdateOverlay(
                        id = id,
                        x = floatOf(obj, "x"),
                        y = floatOf(obj, "y"),
                        scale = floatOf(obj, "scale"),
                        rotationDeg = floatOf(obj, "rot"),
                        text = obj["text"]?.jsonPrimitive?.content,
                        font = obj["font"]?.jsonPrimitive?.content?.let { slot ->
                            MemeFontSlot.entries.firstOrNull { it.name.equals(slot, ignoreCase = true) }
                        },
                        size = intOf(obj, "size"),
                        colorIndex = intOf(obj, "color"),
                        outline = intOf(obj, "outline"),
                        shadow = obj["shadow"]?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
                        fx = obj["fx"]?.jsonPrimitive?.content?.let { raw ->
                            MemeOverlayFx.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                        },
                        clearFx = obj["clearFx"]?.jsonPrimitive?.content == "true",
                        startMs = obj["startMs"]?.jsonPrimitive?.content?.toLongOrNull(),
                        endMs = obj["endMs"]?.jsonPrimitive?.content?.toLongOrNull(),
                    )
                }

                "trim" -> obj["start"]?.jsonPrimitive?.content?.toLongOrNull()?.let { start ->
                    obj["end"]?.jsonPrimitive?.content?.toLongOrNull()?.let { end ->
                        MemeCommand.SetTrim(start, end)
                    }
                }

                "speed" -> obj["rate"]?.jsonPrimitive?.content?.toFloatOrNull()
                    ?.let(MemeCommand::SetSpeed)

                "delay" -> obj["ms"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?.let(MemeCommand::SetFrameDelay)

                "look" -> obj["look"]?.jsonPrimitive?.content
                    ?.let(MemeCommand::SetLook)

                "adjust" -> MemeCommand.SetAdjust(
                    MemeAdjust(
                        brightness = floatOf(obj, "bri") ?: 1f,
                        contrast = floatOf(obj, "con") ?: 1f,
                        saturation = floatOf(obj, "sat") ?: 1f,
                    ),
                )

                "cue-add" -> obj["cue"]?.jsonObject?.let { cue ->
                    val sfx = (cue["sfx"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    if (sfx == null) {
                        null
                    } else {
                        MemeCommand.AddSfxCue(
                            MemeSfxCue(
                                id = (cue["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                    ?: return@let null,
                                sfx = sfx,
                                atMs = (cue["at"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                    ?.toLongOrNull() ?: 0,
                                gain = (cue["g"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                    ?.toFloatOrNull() ?: 1f,
                            ),
                        )
                    }
                }

                "cue-del" -> obj["id"]?.jsonPrimitive?.content
                    ?.let(MemeCommand::RemoveSfxCue)

                "sound" -> MemeCommand.SetSoundtrack(
                    MemeSoundtrack(
                        url = obj["url"]?.jsonPrimitive?.content ?: "",
                        sha256 = obj["sha256"]?.jsonPrimitive?.content ?: "",
                        durationMs = obj["ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                        startMs = obj["start"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                        volume = obj["vol"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 1f,
                        offsetMs = obj["offset"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                        sourceNoteId = obj["src"]?.jsonPrimitive?.content,
                        sourceAuthorPubkey = obj["author"]?.jsonPrimitive?.content,
                        label = obj["label"]?.jsonPrimitive?.content ?: "",
                        loop = obj["loop"]?.jsonPrimitive?.content == "true",
                    ),
                )

                "sound-del" -> MemeCommand.SetSoundtrack(null)

                "stroke-add" -> obj["stroke"]?.jsonObject?.let { stroke ->
                    val id = (stroke["id"] as? JsonPrimitive)?.content
                        ?: "d${stroke.hashCode()}"
                    MemeCommand.AddStroke(
                        MemeStroke(
                            id = id,
                            colorIndex = (stroke["c"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                            widthNorm = (stroke["w"] as? JsonPrimitive)?.content?.toFloatOrNull()
                                ?: MemeProjectContract.DEFAULT_STROKE_WIDTH,
                            points = (stroke["p"] as? kotlinx.serialization.json.JsonArray)
                                ?.mapNotNull { (it as? JsonPrimitive)?.content?.toFloatOrNull() }
                                ?: emptyList(),
                        ),
                    )
                }

                "stroke-del" -> obj["id"]?.jsonPrimitive?.content
                    ?.let(MemeCommand::RemoveStroke)

                "draw-clear" -> MemeCommand.ClearDrawing()

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun floatOf(obj: kotlinx.serialization.json.JsonObject, key: String): Float? =
        obj[key]?.jsonPrimitive?.content?.toFloatOrNull()

    private fun intOf(obj: kotlinx.serialization.json.JsonObject, key: String): Int? =
        obj[key]?.jsonPrimitive?.content?.toIntOrNull()
}
