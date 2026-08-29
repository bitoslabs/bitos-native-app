package space.bitos.app.data.relay

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.bitos.app.data.feed.DefaultRelays
import space.bitos.core.model.RelayEntry
import space.bitos.core.model.RelayListContract
import space.bitos.core.model.RelayUrl

/**
 * APP-018 relays-manager store: owns the persisted managed relay set
 * (versioned [RelayListContract] wire in SharedPreferences) and applies
 * changes to the live [RelayPool] immediately — add/remove edits connect
 * and disconnect sockets on the spot. All rules (bounds, dedupe, NIP-65
 * projection) live in business-core; this adapter only persists, applies
 * and publishes state.
 *
 * Invariants: the set is never empty (a corrupt store falls back to the
 * platform defaults) and every entry keeps at least one role.
 */
class RelayManager(
    private val pool: RelayPool,
    private val prefs: SharedPreferences,
    defaults: List<RelayEntry> = defaultEntries(),
) {
    constructor(context: Context, pool: RelayPool) : this(
        pool = pool,
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val _entries = MutableStateFlow(load(defaults).also { boot -> boot.forEach { pool.add(it.url) } })
    val entries: StateFlow<List<RelayEntry>> = _entries.asStateFlow()

    /** Live per-relay connection states straight from the pool. */
    val connectionStates: StateFlow<Map<RelayUrl, RelayConnectionState>> = pool.statesFlow

    /** Wire JSON for the NIP-65 publish path (NotePublisher). */
    fun encode(): String = RelayListContract.encode(_entries.value)

    /** Adds a relay (read+write by default); false on invalid/duplicate/full. */
    fun add(rawUrl: String): Boolean {
        val url = RelayUrl.parse(rawUrl) ?: return false
        val current = _entries.value
        if (current.any { it.url == url }) return false
        if (current.size >= RelayListContract.MAX_RELAYS) return false
        apply(current + RelayEntry(url, read = true, write = true))
        return true
    }

    /** Removes a relay; the last remaining relay is never removed. */
    fun remove(url: RelayUrl) {
        val next = _entries.value.filterNot { it.url == url }
        if (next.isEmpty()) return
        apply(next)
    }

    /** Sets a relay's roles; an entry would lose both roles → no-op. */
    fun setRoles(url: RelayUrl, read: Boolean, write: Boolean) {
        if (!read && !write) return
        apply(_entries.value.map { if (it.url == url) RelayEntry(url, read, write) else it })
    }

    /** Write-role relays (publish fan-out targets; the primary ⭐ leads). */
    fun writeRelays(): List<RelayUrl> =
        _entries.value.filter { it.write }
            .sortedByDescending { it.primary }
            .map { it.url }

    /** Sets/clears the primary ⭐ (at most one; write relays only). */
    fun setPrimary(url: RelayUrl, primary: Boolean) {
        apply(
            _entries.value.map { entry ->
                when {
                    entry.url == url && primary && entry.write -> entry.copy(primary = true)
                    else -> entry.copy(primary = false)
                }
            },
        )
    }

    private fun apply(next: List<RelayEntry>) {
        val normalized = RelayListContract.normalize(next)
        if (normalized.isEmpty()) return
        val before = _entries.value.map { it.url }.toSet()
        val after = normalized.map { it.url }.toSet()
        (after - before).forEach(pool::add)
        (before - after).forEach(pool::remove)
        prefs.edit().putString(KEY_WIRE, RelayListContract.encode(normalized)).apply()
        _entries.value = normalized
    }

    private fun load(defaults: List<RelayEntry>): List<RelayEntry> {
        val wire = prefs.getString(KEY_WIRE, null) ?: return defaults
        return RelayListContract.decode(wire).ifEmpty { defaults }
    }

    companion object {
        const val PREFS_NAME = "bitos_relays"
        const val KEY_WIRE = "relay_list_v1"

        /** Boot set when nothing is persisted: the platform default relays. */
        fun defaultEntries(): List<RelayEntry> = DefaultRelays.urls.map { url ->
            RelayEntry(
                url = url,
                read = true,
                write = DefaultRelays.writeUrls.any { it == url },
                // RelayPool's primary-read strategy follows configured order;
                // keep the same first default as the NIP-65/write primary.
                primary = url == DefaultRelays.writeUrls.firstOrNull(),
            )
        }

        /** Cold-start set: the persisted managed set, or the defaults. */
        fun bootEntries(prefs: SharedPreferences): List<RelayEntry> {
            val wire = prefs.getString(KEY_WIRE, null) ?: return defaultEntries()
            return RelayListContract.decode(wire).ifEmpty { defaultEntries() }
        }
    }
}
