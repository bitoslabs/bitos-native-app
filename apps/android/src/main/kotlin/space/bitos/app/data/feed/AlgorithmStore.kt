package space.bitos.app.data.feed

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.bitos.core.feed.AlgorithmContract
import space.bitos.core.feed.AlgorithmPresetId
import space.bitos.core.feed.AlgorithmSignal
import space.bitos.core.feed.AlgorithmSnapshot
import space.bitos.core.feed.AlgorithmSurface
import space.bitos.core.feed.SignalSetting
import space.bitos.core.feed.SurfaceSetting

/**
 * APP-018 algorithm-preferences adapter: persists the versioned
 * [AlgorithmContract] wire in SharedPreferences and publishes the typed
 * snapshot. All rules (presets, steps, freshness, normalization) live in
 * business-core; this adapter only persists and notifies.
 */
class AlgorithmStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val _snapshot = MutableStateFlow(load())
    val snapshot: StateFlow<AlgorithmSnapshot> = _snapshot.asStateFlow()

    fun reload() {
        _snapshot.value = load()
    }

    /** Master switch per surface (off = strict chronological). */
    fun setEnabled(surface: AlgorithmSurface, enabled: Boolean) = update(surface) { it.copy(enabled = enabled) }

    /** One-tap preset (also re-enables the surface). */
    fun setPreset(surface: AlgorithmSurface, preset: AlgorithmPresetId) {
        mutate { snapshot ->
            snapshot.copy(
                surfaces = snapshot.surfaces + (surface to AlgorithmContract.preset(surface, preset)),
            )
        }
    }

    fun setSignal(surface: AlgorithmSurface, signal: AlgorithmSignal, enabled: Boolean, weight: Double) =
        update(surface) { setting ->
            setting.copy(
                signals = setting.signals + (signal to SignalSetting(enabled, weight)),
            )
        }

    /** Freshness half-life (Live 1h · Balanced 6h · Relaxed 24h · Chill 3d). */
    fun setFreshness(hours: Int) = mutate { it.copy(freshnessHours = hours) }

    /** Active preset for the surface (CUSTOM once the user strays). */
    fun detectPreset(surface: AlgorithmSurface): AlgorithmPresetId =
        AlgorithmContract.detectPreset(surface, _snapshot.value.surfaces[surface] ?: AlgorithmContract.preset(surface, AlgorithmPresetId.BALANCED))

    private fun update(surface: AlgorithmSurface, transform: (SurfaceSetting) -> SurfaceSetting) = mutate { snapshot ->
        val current = snapshot.surfaces[surface] ?: AlgorithmContract.preset(surface, AlgorithmPresetId.BALANCED)
        snapshot.copy(surfaces = snapshot.surfaces + (surface to transform(current)))
    }

    private fun mutate(transform: (AlgorithmSnapshot) -> AlgorithmSnapshot) {
        val next = AlgorithmContract.normalize(transform(_snapshot.value))
        prefs.edit().putString(KEY_WIRE, AlgorithmContract.encode(next)).apply()
        _snapshot.value = next
    }

    private fun load(): AlgorithmSnapshot =
        prefs.getString(KEY_WIRE, null)?.let(AlgorithmContract::decode) ?: AlgorithmSnapshot()

    companion object {
        const val PREFS_NAME = "bitos_algo"
        const val KEY_WIRE = "algo_prefs_v1"
    }
}
