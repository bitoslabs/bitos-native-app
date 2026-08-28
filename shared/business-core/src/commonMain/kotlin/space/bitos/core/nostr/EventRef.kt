package space.bitos.core.nostr

/**
 * APP-009 root resolution: a `note1`/`nevent1`/`naddr1` reference (with or
 * without the `nostr:` prefix) parsed into an addressable event pointer —
 * id or NIP-33 coordinate — plus author and relay hints from the TLV.
 * Parsing is lenient-but-strict: invalid checksums, wrong lengths or
 * malformed TLV yield null (callers surface "invalid id").
 */
sealed interface EventRef {
    val relayHints: List<String>

    data class ById(
        val id: String,
        val authorPubkey: String?,
        override val relayHints: List<String>,
    ) : EventRef

    data class ByCoordinate(
        val kind: Int,
        val pubkey: String,
        val d: String,
        val authorPubkey: String,
        override val relayHints: List<String>,
    ) : EventRef
}

object EventRefs {

    /** Bounded relay hints (NIP-19 payloads can carry any number). */
    const val MAX_RELAY_HINTS = 4

    fun parse(raw: String): EventRef? {
        var value = raw.trim()
        if (value.startsWith("nostr:")) value = value.removePrefix("nostr:")
        val hrp = value.substringBefore('1', "")
        if (value.length < 8) return null
        return when (hrp) {
            "note", "nevent", "naddr" -> decode(hrp, value)
            else -> null
        }
    }

    private fun decode(hrp: String, encoded: String): EventRef? {
        val bytes = Nip27.decodeBech32(hrp, encoded) ?: return null
        return when (hrp) {
            "note" -> bytes.takeIf { it.size == 32 }?.let { EventRef.ById(hex(it), null, emptyList()) }
            "nevent" -> nevent(tlv(bytes))
            "naddr" -> naddr(tlv(bytes))
            else -> null
        }
    }

    private fun nevent(entries: List<TlvEntry>): EventRef.ById? {
        val id = entries.firstOrNull { it.type == 0 }?.value?.takeIf { it.size == 32 } ?: return null
        val author = entries.firstOrNull { it.type == 1 }?.value?.takeIf { it.size == 32 }
        return EventRef.ById(
            id = hex(id),
            authorPubkey = author?.let(::hex),
            relayHints = relayHints(entries),
        )
    }

    private fun naddr(entries: List<TlvEntry>): EventRef.ByCoordinate? {
        val special = entries.firstOrNull { it.type == 0 }?.value ?: return null
        val coordinate = special.decodeToString()
        val parts = coordinate.split(':')
        if (parts.size != 3) return null
        val kind = parts[0].toIntOrNull() ?: return null
        val pubkey = parts[1]
        val d = parts[2]
        if (pubkey.length != 64 || pubkey.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        if (d.isEmpty() || d.length > 64) return null
        val author = entries.firstOrNull { it.type == 1 }?.value
        return EventRef.ByCoordinate(
            kind = kind,
            pubkey = pubkey,
            d = d,
            authorPubkey = author?.takeIf { it.size == 32 }?.let(::hex) ?: pubkey,
            relayHints = relayHints(entries),
        )
    }

    private fun relayHints(entries: List<TlvEntry>): List<String> =
        entries.filter { it.type == 2 }
            .map { it.value.decodeToString() }
            .filter { it.startsWith("wss://") || it.startsWith("ws://") }
            .distinct()
            .take(MAX_RELAY_HINTS)

    /**
     * Thread-root REQ filter (the platform wraps it via the codec): by id,
     * or by NIP-33 coordinate (newest `#d` version — limit 1, relays sort).
     */
    fun requestFilter(ref: EventRef): String = when (ref) {
        is EventRef.ById -> """{"kinds":[1,22],"ids":["${ref.id}"],"limit":1}"""
        is EventRef.ByCoordinate -> """{"kinds":[${ref.kind}],"authors":["${ref.pubkey}"],"#d":["${ref.d}"],"limit":1}"""
    }

    // ── minimal TLV (NIP-19): [type][length][value]… ───────────────────

    private data class TlvEntry(val type: Int, val value: ByteArray)

    private fun tlv(bytes: ByteArray): List<TlvEntry> {
        val entries = mutableListOf<TlvEntry>()
        var index = 0
        while (index + 1 < bytes.size) {
            val type = bytes[index].toInt()
            val length = bytes[index + 1].toInt()
            if (length < 0 || index + 2 + length > bytes.size) break
            entries += TlvEntry(type, bytes.copyOfRange(index + 2, index + 2 + length))
            index += 2 + length
        }
        return entries
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            out.append("0123456789abcdef"[(byte.toInt() shr 4) and 0xf])
            out.append("0123456789abcdef"[byte.toInt() and 0xf])
        }
        return out.toString()
    }

    /** Test/vector helper: encodes one TLV entry (test-only usage). */
    internal fun encodeTlvEntry(type: Int, value: ByteArray): ByteArray =
        byteArrayOf(type.toByte(), value.size.toByte()) + value
}
