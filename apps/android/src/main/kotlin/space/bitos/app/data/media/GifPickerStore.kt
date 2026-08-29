package space.bitos.app.data.media

import android.content.Context
import space.bitos.core.publish.CachedGifs
import space.bitos.core.publish.GifPickerContract

/**
 * APP-008 GIF picker cache adapter: the versioned wire (shared
 * `GifPickerContract`) in SharedPreferences — recent picks plus the last
 * trending page. Quota failures are safe to ignore: the cache is an
 * enhancement, never a blocker.
 */
class GifPickerStore(context: Context) {
    private val prefs = context.getSharedPreferences("bitos_gif_picker", Context.MODE_PRIVATE)

    fun load(): CachedGifs? =
        prefs.getString(KEY_WIRE, null)?.let(GifPickerContract::cacheDecode)

    fun save(cache: CachedGifs) {
        runCatching {
            prefs.edit()
                .putString(KEY_WIRE, GifPickerContract.cacheEncode(cache.recent, cache.trending, cache.savedAtMs))
                .apply()
        }
    }

    private companion object {
        const val KEY_WIRE = "wire"
    }
}
