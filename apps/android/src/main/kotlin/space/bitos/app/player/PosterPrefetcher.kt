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

    fun prefetch(urls: List<String>, widthPx: Int, heightPx: Int) {
        cancel()
        requests = urls.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_REQUESTS)
            .map { url ->
                imageLoader.enqueue(
                    ImageRequest.Builder(applicationContext)
                        .data(url)
                        .size(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
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
    }

    private companion object {
        const val MAX_REQUESTS = 12
    }
}
