package space.bitos.app.player

import android.content.Context
import coil.ImageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.imageLoader

/**
 * Bounded Coil request owner for the Explore grid's next poster window.
 * Replacing a window cancels obsolete work; Coil's memory/disk caches remain
 * keyed by URL plus requested decode size.
 */
class PosterPrefetcher(context: Context) {
    private val applicationContext = context.applicationContext
    private val imageLoader: ImageLoader = applicationContext.imageLoader
    private var requests: List<Disposable> = emptyList()
    /** Identical windows are common after unrelated feed state publishes.
     * Keep their requests alive instead of cancelling/restarting them. */
    private var activeWindow: Window? = null

    fun prefetch(urls: List<String>, widthPx: Int, heightPx: Int) {
        val normalizedUrls = urls.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_REQUESTS)
            .toList()
        val window = Window(
            urls = normalizedUrls,
            widthPx = widthPx.coerceAtLeast(1),
            heightPx = heightPx.coerceAtLeast(1),
        )
        if (window == activeWindow) return
        cancel()
        activeWindow = window
        requests = normalizedUrls.asSequence()
            .map { url ->
                imageLoader.enqueue(
                    ImageRequest.Builder(applicationContext)
                        .data(url)
                        .size(window.widthPx, window.heightPx)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .networkCachePolicy(CachePolicy.ENABLED)
                        .build(),
                )
            }
            .toList()
    }

    fun cancel() {
        requests.forEach(Disposable::dispose)
        requests = emptyList()
        activeWindow = null
    }

    private data class Window(val urls: List<String>, val widthPx: Int, val heightPx: Int)

    private companion object {
        const val MAX_REQUESTS = 12
    }
}
