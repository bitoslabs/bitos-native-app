package space.bitos.core.nostr

import space.bitos.core.identity.NostrKeyCodec

/**
 * Inbound deep-link classifier (spec §1.2): `nostr:` entities route to a
 * profile or a note thread, `lightning:` invoices open the invoice viewer.
 * Pure and shared so both platforms resolve identical URIs identically —
 * `nprofile` TLV hints and relay hints inside `nevent1`/`naddr1` stay with
 * the existing [EventRefs] machinery the thread fetch already uses.
 */
object DeepLinks {

    sealed interface Target {
        /** npub / nprofile → the author's profile (pubkey hex). */
        data class Author(val pubkeyHex: String) : Target

        /** note1 / nevent1 / naddr1 (± `nostr:` prefix) → the thread head. */
        data class Note(val reference: String) : Target

        /** `lightning:` / `lightning=lap:` BOLT-11 invoice URI (raw). */
        data class Lightning(val invoiceUri: String) : Target
    }

    /** Classifies one inbound URI; null = not a BitOS deep link. */
    fun classify(uri: String): Target? {
        val trimmed = uri.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URI) return null
        val lowered = trimmed.lowercase()
        return when {
            lowered.startsWith("nostr:") -> entity(trimmed.removePrefix("nostr:").removePrefix("NOSTR:"))
            lowered.startsWith("lightning:") -> Target.Lightning(trimmed)
            // Plain entities (no scheme) classify too — in-app handoffs.
            else -> entity(trimmed)
        }
    }

    private fun entity(value: String): Target? {
        if (value.isEmpty()) return null
        // npub: direct pubkey; nprofile: TLV pubkey (EventRefs ignores the
        // key form, so decode the bech32 manually through the key codec).
        if (value.startsWith("npub1")) {
            return NostrKeyCodec.parseNpub(value)?.let(Target::Author)
        }
        if (value.startsWith("nprofile1")) {
            return EventRefs.tlvPubkey(value)?.let(Target::Author)
        }
        if (value.startsWith("note1") || value.startsWith("nevent1") || value.startsWith("naddr1")) {
            return EventRefs.parse(value)?.let { Target.Note(value) }
        }
        return null
    }

    private const val MAX_URI = 2_048
}
