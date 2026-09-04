package space.bitos.app.data.relay

import space.bitos.core.model.NostrEvent
import space.bitos.core.model.RelayUrl

/**
 * One relay frame after the pool's shared decode-once stage (performance
 * audit Phase 2): EVENT frames arrive ID-hash-checked and BIP-340-verified
 * exactly once per process, and feature stores consume them directly —
 * they keep only policy logic. [Verified.message] rides along for the few
 * seams that still parse relay JSON (e.g. embedded zap-request ids).
 */
sealed interface VerifiedPoolFrame {
    /** `["EVENT", subId, event]` that passed both display-trust stages. */
    data class Verified(
        val event: NostrEvent,
        /** Delivery subscription id; null when absent or oversized. */
        val subscriptionId: String?,
        val relay: RelayUrl,
        val message: String,
    ) : VerifiedPoolFrame

    /** `["EOSE", subId]` completion signal. */
    data class Eose(
        val subscriptionId: String,
        val relay: RelayUrl,
    ) : VerifiedPoolFrame
}
