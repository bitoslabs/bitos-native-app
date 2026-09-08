package space.bitos.app.ui.create

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import space.bitos.app.data.media.GifPickerStore
import coil.compose.AsyncImage
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.publish.CachedGifs
import space.bitos.core.publish.GifChoice
import space.bitos.core.publish.GifPickerContract

/**
 * APP-008 GIF picker (legacy Flutter `GifPickerSheet` / web
 * `GifPicker.svelte` parity): trending on open, 350 ms debounced search,
 * Recent tab (≤12, persisted), 24 h trending cache, "Load more"
 * pagination, three-column animated preview grid and the "Powered by Giphy"
 * footer.
 * Picking hands back the full-resolution URL — the composer embeds it as
 * a plain image URL. All rules run in shared `GifPickerContract`; this
 * sheet owns HTTP, tiles and persistence only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GifPickerSheet(
    onPick: (GifChoice) -> Unit,
    onDismiss: () -> Unit,
    /** Open on the STICKERS tab (transparent cut-outs — what meme layers
     *  want on top of the media; the composer default stays GIFs). */
    defaultStickers: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { GifPickerStore(context) }
    val client = remember {
        OkHttpClient.Builder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<GifChoice>>(emptyList()) }
    var trendingSnapshot by remember { mutableStateOf<List<GifChoice>>(emptyList()) }
    var trendingSavedAt by remember { mutableStateOf(0L) }
    // Web parity: GIFs vs Stickers — Giphy's sticker endpoints return
    // transparent cut-outs. Each kind keeps its own trending snapshot.
    var stickersTab by remember { mutableStateOf(defaultStickers) }
    var stickersSnapshot by remember { mutableStateOf<List<GifChoice>>(emptyList()) }
    var stickersSavedAt by remember { mutableStateOf(0L) }
    var recent by remember { mutableStateOf<List<GifChoice>>(emptyList()) }
    var recentTab by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var nextOffset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var restored by remember { mutableStateOf(false) }
    // Debounce guard: only the newest request may apply its results.
    var generation by remember { mutableStateOf(0) }

    fun persist() {
        store.save(
            CachedGifs(
                recent = recent,
                trending = trendingSnapshot,
                savedAtMs = trendingSavedAt,
                stickersTrending = stickersSnapshot,
                stickersSavedAtMs = stickersSavedAt,
                stickersKind = stickersTab,
            ),
        )
    }

    fun fetch(pageQuery: String, append: Boolean) {
        if (append && (loading || loadingMore || !hasMore)) return
        val mine = ++generation
        if (append) loadingMore = true else loading = true
        error = ""
        scope.launch {
            try {
                val offset = if (append) nextOffset else 0
                val body = withContext(Dispatchers.IO) {
                    client.newCall(
                        Request.Builder()
                            .url(
                                GifPickerContract.buildUrl(
                                    GifPickerContract.DEFAULT_API_KEY, pageQuery.trim(), offset,
                                    stickers = stickersTab,
                                ),
                            )
                            .build(),
                    ).execute().use { response ->
                        if (!response.isSuccessful) throw IllegalStateException("Giphy ${response.code}")
                        response.body?.string() ?: throw IllegalStateException("Giphy empty body")
                    }
                }
                val fetched = GifPickerContract.parseChoices(body)
                val page = GifPickerContract.pagination(body, fetched.size, offset)
                if (mine != generation) return@launch
                items = if (append) items + fetched.filter { fresh -> items.none { it.id == fresh.id } } else fetched
                nextOffset = page.nextOffset
                hasMore = page.hasMore
                if (pageQuery.isBlank()) {
                    if (stickersTab) {
                        stickersSnapshot = items
                        stickersSavedAt = System.currentTimeMillis()
                    } else {
                        trendingSnapshot = items
                        trendingSavedAt = System.currentTimeMillis()
                    }
                    persist()
                }
                if (items.isEmpty() && pageQuery.isNotBlank()) {
                    error = "No GIFs matched \"${pageQuery.trim()}\"."
                }
            } catch (failure: Exception) {
                if (mine == generation) error = "Couldn't load GIFs. Check your connection."
            } finally {
                if (mine == generation) {
                    loading = false
                    loadingMore = false
                }
            }
        }
    }

    fun pick(gif: GifChoice) {
        recent = GifPickerContract.mergeRecent(recent, gif)
        persist()
        onDismiss()
        onPick(gif)
    }

    // Restore once (24 h fresh cache seeds trending + recents), then the
    // first network page when the cache came up empty.
    LaunchedEffect(Unit) {
        val cache = store.load()
        if (cache != null) {
            recent = cache.recent
            // The remembered tab wins unless the caller asked for stickers.
            stickersTab = defaultStickers || cache.stickersKind
            stickersSnapshot = cache.stickersTrending
            stickersSavedAt = cache.stickersSavedAtMs
            val gifsFresh = GifPickerContract.isCacheFresh(cache.savedAtMs, System.currentTimeMillis())
            if (gifsFresh && cache.trending.isNotEmpty()) {
                trendingSnapshot = cache.trending
                trendingSavedAt = cache.savedAtMs
            }
            val stickersFresh = GifPickerContract.isCacheFresh(cache.stickersSavedAtMs, System.currentTimeMillis())
            val cachedStickers = if (stickersFresh) cache.stickersTrending else emptyList()
            val seed = if (stickersTab) cachedStickers else if (gifsFresh) cache.trending else emptyList()
            if (seed.isNotEmpty() && items.isEmpty()) {
                items = seed
                nextOffset = seed.size
            }
        }
        restored = true
        if (items.isEmpty()) fetch("", append = false)
    }
    // Kind switch: reset to that kind's page (cached seed or refetch).
    LaunchedEffect(stickersTab) {
        if (!restored) return@LaunchedEffect
        recentTab = false
        hasMore = true
        nextOffset = 0
        items = emptyList()
        val snapshot = if (stickersTab) stickersSnapshot else trendingSnapshot
        if (snapshot.isNotEmpty()) {
            items = snapshot
            nextOffset = snapshot.size
        } else {
            fetch("", append = false)
        }
    }
    // Search-as-you-type (legacy 350 ms debounce).
    LaunchedEffect(query) {
        if (!restored) return@LaunchedEffect
        delay(GifPickerContract.SEARCH_DEBOUNCE_MS)
        recentTab = false
        fetch(query, append = false)
    }

    val visibleItems = if (recentTab) recent else items

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = BitOSColors.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f),
        ) {
            // Header: ONE row — small search field + the inline kind
            // segment (GIFs vs Stickers). Saves a full chrome row for the
            // grid; stickers = transparent cut-outs (web parity).
            Row(
                Modifier.padding(horizontal = BitOSSpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
            ) {
                Box(Modifier.weight(1f)) {
                    // Small search field: compact 48 dp row + label-size
                    // text; the placeholder follows the active kind.
                    space.bitos.app.ui.components.BitosTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = if (stickersTab) "Search stickers…" else "Search GIFs",
                        singleLine = true,
                        compact = true,
                        leadingIcon = {
                            Icon(
                                AppIcons.Search,
                                contentDescription = null,
                                tint = BitOSColors.textSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        textStyle = MaterialTheme.typography.labelLarge.copy(color = BitOSColors.textPrimary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(BitOSSpacing.sm),
                    color = BitOSColors.surfaceElevated.copy(alpha = 0.35f),
                    // Same height as the compact search field (48 dp) —
                    // the header reads as ONE control row.
                    modifier = Modifier.height(48.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxHeight()
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        KindPill("GIFs", selected = !stickersTab) { stickersTab = false }
                        KindPill("Stickers", selected = stickersTab) { stickersTab = true }
                    }
                }
                if (loading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = BitOSColors.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // Recent / Trending pills (only with recents — legacy parity).
            if (query.isBlank() && recent.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(BitOSSpacing.md),
                    color = BitOSColors.surfaceElevated.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BitOSSpacing.lg, vertical = BitOSSpacing.sm)
                        .padding(all = BitOSSpacing.xs),
                ) {
                    Row {
                        TabPill("Recent", selected = recentTab, modifier = Modifier.weight(1f)) { recentTab = true }
                        Spacer(Modifier.width(BitOSSpacing.xs))
                        TabPill("Trending", selected = !recentTab, modifier = Modifier.weight(1f)) { recentTab = false }
                    }
                }
            }
            // Grid.
            Box(Modifier.weight(1f)) {
                when {
                    error.isNotEmpty() -> EmptyState(text = error)
                    visibleItems.isEmpty() && !loading -> EmptyState(text = if (recentTab) "No recent GIFs yet." else "No GIFs yet.")
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = BitOSSpacing.lg,
                            end = BitOSSpacing.lg,
                            top = BitOSSpacing.sm,
                            bottom = BitOSSpacing.md,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(visibleItems, key = { if (it.id.isEmpty()) it.url else it.id }) { gif ->
                            Box(
                                Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(BitOSSpacing.sm))
                                    .background(BitOSColors.surfaceElevated)
                                    .clickable(onClickLabel = "Pick GIF") { pick(gif) },
                            ) {
                                GifPreviewTile(preview = gif.preview)
                            }
                        }
                        if (!recentTab && hasMore && visibleItems.isNotEmpty()) {
                            item(span = { GridItemSpan(3) }) {
                                Box(Modifier.fillMaxWidth().padding(vertical = BitOSSpacing.sm), contentAlignment = Alignment.Center) {
                                    OutlinedButton(
                                        onClick = { fetch(query, append = true) },
                                        enabled = !loadingMore,
                                    ) {
                                        if (loadingMore) {
                                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                        } else {
                                            Text("Load more")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Powered by Giphy footer (web parity).
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Chat, contentDescription = null, tint = BitOSColors.textTertiary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("Powered by Giphy", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = BitOSColors.textTertiary)
            }
        }
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, modifier: Modifier = Modifier, onTap: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(BitOSSpacing.sm))
            // The app's chip language: accent-tint fill + accent text when
            // selected (surface-on-surface was near-invisible).
            .background(if (selected) BitOSColors.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClickLabel = label) { onTap() }
            // Medium tab: comfortable touch height + labelMedium text.
            .padding(horizontal = BitOSSpacing.sm, vertical = BitOSSpacing.sm)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.W600 else FontWeight.W400,
            color = if (selected) BitOSColors.primary else BitOSColors.textSecondary,
        )
    }
}

/** Compact inline kind pill (GIFs/Stickers) trailing the search field —
 *  fills the 48 dp segment so the touch target is the whole pill. */
@Composable
private fun KindPill(label: String, selected: Boolean, onTap: () -> Unit) {
    Box(
        Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(BitOSSpacing.xs))
            .background(if (selected) BitOSColors.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClickLabel = label) { onTap() }
            .padding(horizontal = BitOSSpacing.sm)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.W600 else FontWeight.W400,
            color = if (selected) BitOSColors.primary else BitOSColors.textSecondary,
        )
    }
}

@Composable
private fun GifPreviewTile(preview: String) {
    AsyncImage(
        model = preview,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
        placeholder = ColorPainter(BitOSColors.surfaceElevated),
        error = rememberVectorPainter(AppIcons.BrokenImage),
    )
}

@Composable
private fun EmptyState(text: String) {
    Column(
        Modifier.fillMaxSize().padding(BitOSSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(AppIcons.Photo, contentDescription = null, tint = BitOSColors.textSecondary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(BitOSSpacing.sm))
        Text(text, style = MaterialTheme.typography.bodySmall, color = BitOSColors.textSecondary)
    }
}
