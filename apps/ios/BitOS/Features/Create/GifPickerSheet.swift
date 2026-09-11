import BusinessCore
import SwiftUI

/// One GIF from the picker — the full-resolution URL is embedded in the
/// note, the preview renders the grid tile (shared `GifPickerContract`
/// item JSON shape via the bridge).
struct GifChoiceItem: Identifiable, Equatable {
    let id: String
    let url: String
    let preview: String
    let width: Int
    let height: Int

    var idKey: String { id.isEmpty ? url : id }

    static func from(jsonArray: [[String: Any]]) -> [GifChoiceItem] {
        jsonArray.compactMap { obj in
            guard let url = obj["url"] as? String,
                  let preview = obj["preview"] as? String else { return nil }
            return GifChoiceItem(
                id: (obj["id"] as? String) ?? "",
                url: url,
                preview: preview,
                width: (obj["w"] as? Int) ?? 120,
                height: (obj["h"] as? Int) ?? 120
            )
        }
    }

    var itemJson: String {
        let obj: [String: Any] = ["id": id, "url": url, "preview": preview, "w": width, "h": height]
        guard let data = try? JSONSerialization.data(withJSONObject: obj),
              let json = String(data: data, encoding: .utf8) else { return "{}" }
        return json
    }
}

/**
 * APP-008 GIF picker (legacy Flutter `GifPickerSheet` / web
 * `GifPicker.svelte` parity): trending on open, 350 ms debounced search,
 * Recent tab (≤12, persisted), 24 h trending cache, "Load more"
 * pagination, two-column preview grid and the "Powered by Giphy" footer.
 * All rules run in shared `GifPickerContract` through the bridge; this
 * view owns HTTP, tiles and persistence only.
 */
struct GifPickerSheet: View {
    let onPick: (GifChoiceItem) -> Void
    let onDismiss: () -> Void
    /// Open on the STICKERS tab (transparent cut-outs — what meme layers
    /// want on top of the media; the composer default stays GIFs).
    var defaultStickers: Bool = false

    private let bridge = BusinessCoreBridge()
    private let cacheKey = "bitos_gif_picker_v1"

    @State private var query = ""
    @State private var items: [GifChoiceItem] = []
    @State private var trendingSnapshot: [GifChoiceItem] = []
    @State private var trendingSavedAt: Double = 0
    // Web parity: GIFs vs Stickers — Giphy's sticker endpoints return
    // transparent cut-outs. Each kind keeps its own trending snapshot.
    @State private var stickersTab = false
    @State private var stickersSnapshot: [GifChoiceItem] = []
    @State private var stickersSavedAt: Double = 0
    @State private var recent: [GifChoiceItem] = []
    @State private var recentTab = false
    @State private var loading = false
    @State private var loadingMore = false
    @State private var errorText = ""
    @State private var nextOffset = 0
    @State private var hasMore = true
    @State private var restored = false
    @State private var generation = 0

    private var visibleItems: [GifChoiceItem] { recentTab ? recent : items }

    var body: some View {
        VStack(spacing: 0) {
            searchHeader
            if query.trimmingCharacters(in: .whitespaces).isEmpty && !recent.isEmpty {
                tabPills
            }
            grid
            footer
        }
        .background(BitOSTheme.surface)
        .task { restoreAndLoad() }
        // Search-as-you-type: .task(id:) cancels the previous debounce
        // when the query changes (shared 350 ms rule).
        .task(id: query) {
            guard restored else { return }
            try? await Task.sleep(nanoseconds: UInt64(bridge.gifPickerDebounceMs()) * 1_000_000)
            guard !Task.isCancelled else { return }
            recentTab = false
            await fetch(query, append: false)
        }
        .onChange(of: stickersTab) { _, _ in
            guard restored else { return }
            recentTab = false
            hasMore = true
            nextOffset = 0
            items = []
            let snapshot = stickersTab ? stickersSnapshot : trendingSnapshot
            if !snapshot.isEmpty {
                items = snapshot
                nextOffset = snapshot.count
            } else {
                Task { await fetch("", append: false) }
            }
        }
    }

    // MARK: - Pieces

    /// ONE header row: the small search field + the inline GIFs/Stickers
    /// segment (saves a full chrome row for the grid); the placeholder
    /// follows the active kind.
    private var searchHeader: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            HStack(spacing: BitOSTheme.Spacing.sm) {
                AppIcons.image(for: AppIcons.search)
                    .font(.system(size: 12))
                    .foregroundStyle(BitOSTheme.textSecondary)
                TextField(
                    stickersTab ? "Search stickers…" : "Search GIFs",
                    text: $query
                )
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.system(size: 13))
                .foregroundStyle(BitOSTheme.textPrimary)
                if loading {
                    ProgressView()
                        .controlSize(.small)
                        .tint(BitOSTheme.accent)
                }
            }
            .padding(.horizontal, 10)
            .frame(maxWidth: .infinity)
            .frame(height: 34)
            .background(
                RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous)
                    .fill(BitOSTheme.surfaceElevated.opacity(0.45))
            )
            kindSegment
        }
        .padding(.horizontal, BitOSTheme.Spacing.lg)
        .padding(.top, BitOSTheme.Spacing.md)
        .padding(.bottom, BitOSTheme.Spacing.sm)
    }

    /// Compact trailing GIFs/Stickers segment — the SAME height as the
    /// search field (34 pt): the header reads as ONE control row.
    private var kindSegment: some View {
        HStack(spacing: 2) {
            kindPill("GIFs", selected: !stickersTab) { stickersTab = false }
            kindPill("Stickers", selected: stickersTab) { stickersTab = true }
        }
        .padding(3)
        .frame(height: 34)
        .background(
            RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm, style: .continuous)
                .fill(BitOSTheme.surfaceElevated.opacity(0.35))
        )
    }

    /// Kind pill fills the segment height — the touch target is the pill.
    private func kindPill(_ label: String, selected: Bool, onTap: @escaping () -> Void) -> some View {
        Button(action: onTap) {
            Text(label)
                .font(.system(size: 13, weight: selected ? .semibold : .regular))
                .foregroundStyle(selected ? BitOSTheme.accent : BitOSTheme.textSecondary)
                .frame(maxHeight: .infinity)
                .padding(.horizontal, BitOSTheme.Spacing.sm)
                .background(
                    RoundedRectangle(cornerRadius: BitOSTheme.Radius.xs, style: .continuous)
                        .fill(selected ? BitOSTheme.accent.opacity(0.18) : .clear)
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private var tabPills: some View {
        HStack(spacing: BitOSTheme.Spacing.xs) {
            tabPill("Recent", selected: recentTab) { recentTab = true }
            tabPill("Trending", selected: !recentTab) { recentTab = false }
        }
        .padding(BitOSTheme.Spacing.xs)
        .background(
            RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous)
                .fill(BitOSTheme.surfaceElevated.opacity(0.35))
        )
        .padding(.horizontal, BitOSTheme.Spacing.lg)
        .padding(.bottom, BitOSTheme.Spacing.sm)
    }

    /// Medium tab: 13 pt semibold, comfortable touch height, and the
    /// app's accent chip language for the selected state (surface-on-
    /// surface was near-invisible).
    private func tabPill(_ label: String, selected: Bool, onTap: @escaping () -> Void) -> some View {
        Button(action: onTap) {
            Text(label)
                .font(.system(size: 13, weight: selected ? .semibold : .regular))
                .foregroundStyle(selected ? BitOSTheme.accent : BitOSTheme.textSecondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, BitOSTheme.Spacing.sm)
                .background(
                    RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm, style: .continuous)
                        .fill(selected ? BitOSTheme.accent.opacity(0.18) : .clear)
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private var grid: some View {
        Group {
            if !errorText.isEmpty {
                emptyState(icon: AppIcons.photo, text: errorText)
            } else if visibleItems.isEmpty && !loading {
                emptyState(icon: AppIcons.photo, text: recentTab ? "No recent GIFs yet." : "No GIFs yet.")
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: BitOSTheme.Spacing.sm), GridItem(.flexible(), spacing: BitOSTheme.Spacing.sm)], spacing: BitOSTheme.Spacing.sm) {
                        ForEach(visibleItems, id: \.idKey) { gif in
                            Button {
                                pick(gif)
                            } label: {
                                GifPreviewTile(preview: gif.preview)
                                    .aspectRatio(1, contentMode: .fit)
                                    .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm, style: .continuous))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Pick GIF")
                        }
                        if !recentTab && hasMore && !visibleItems.isEmpty {
                            Button {
                                Task { await fetch(query, append: true) }
                            } label: {
                                Group {
                                    if loadingMore {
                                        ProgressView().controlSize(.small)
                                    } else {
                                        Text("Load more")
                                    }
                                }
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, BitOSTheme.Spacing.sm)
                            }
                            .buttonStyle(.bordered)
                            .disabled(loadingMore)
                            .gridCellColumns(2)
                        }
                    }
                    .padding(.horizontal, BitOSTheme.Spacing.lg)
                    .padding(.top, BitOSTheme.Spacing.sm)
                    .padding(.bottom, BitOSTheme.Spacing.md)
                }
            }
        }
    }

    private var footer: some View {
        HStack(spacing: 4) {
            AppIcons.image(for: AppIcons.info)
                .font(.system(size: 10))
                .foregroundStyle(BitOSTheme.textTertiary)
            Text("Powered by Giphy")
                .font(.system(size: 10))
                .foregroundStyle(BitOSTheme.textTertiary)
        }
        .padding(.vertical, 6)
    }

    private func emptyState(icon symbol: String, text: String) -> some View {
        VStack(spacing: BitOSTheme.Spacing.sm) {
            AppIcons.image(for: symbol)
                .font(.system(size: 26))
                .foregroundStyle(BitOSTheme.textSecondary)
            Text(text)
                .font(.system(size: 12))
                .foregroundStyle(BitOSTheme.textSecondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Data

    /// Bridge map values arrive as native Swift types ([String: Any]).
    private func intFrom(_ map: [String: Any], _ key: String) -> Int {
        (map[key] as? Int) ?? ((map[key] as? NSNumber)?.intValue ?? 0)
    }

    private func restoreAndLoad() {
        if let wire = UserDefaults.standard.string(forKey: cacheKey),
           let envelope = bridge.gifCacheDecode(json: wire),
           let data = envelope.data(using: .utf8),
           let cache = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            recent = itemsFrom(cache["recent"])
            let gifsSavedAt = (cache["savedAt"] as? NSNumber)?.doubleValue ?? 0
            if bridge.gifCacheFresh(savedAtMs: Int64(gifsSavedAt), nowMs: nowMs) {
                let trending = itemsFrom(cache["trending"])
                trendingSnapshot = trending
                trendingSavedAt = gifsSavedAt
            }
            let stickersSavedAtValue = (cache["stickersSavedAt"] as? NSNumber)?.doubleValue ?? 0
            if bridge.gifCacheFresh(savedAtMs: Int64(stickersSavedAtValue), nowMs: nowMs) {
                stickersSnapshot = itemsFrom(cache["stickersTrending"])
                stickersSavedAt = stickersSavedAtValue
            }
            // The remembered tab wins unless the caller asked for stickers.
            stickersTab = defaultStickers || ((cache["stickersKind"] as? NSNumber)?.boolValue ?? false)
            let seed = stickersTab ? stickersSnapshot : trendingSnapshot
            if !seed.isEmpty && items.isEmpty {
                items = seed
                nextOffset = seed.count
            }
        }
        restored = true
        if items.isEmpty {
            Task { await fetch("", append: false) }
        }
    }

    private func itemsFrom(_ node: Any?) -> [GifChoiceItem] {
        guard let array = node as? [[String: Any]] else { return [] }
        return GifChoiceItem.from(jsonArray: array)
    }

    private func persist() {
        let recentJson = "[" + recent.map(\.itemJson).joined(separator: ",") + "]"
        let trendingJson = "[" + trendingSnapshot.map(\.itemJson).joined(separator: ",") + "]"
        let stickersJson = "[" + stickersSnapshot.map(\.itemJson).joined(separator: ",") + "]"
        let wire = bridge.gifCacheEncode(
            recentJson: recentJson,
            trendingJson: trendingJson,
            savedAtMs: Int64(trendingSavedAt),
            stickersTrendingJson: stickersJson,
            stickersSavedAtMs: Int64(stickersSavedAt),
            stickersKind: stickersTab
        )
        UserDefaults.standard.set(wire, forKey: cacheKey)
    }

    private func fetch(_ pageQuery: String, append: Bool) async {
        if append && (loading || loadingMore || !hasMore) { return }
        let mine = generation + 1
        generation = mine
        if append { loadingMore = true } else { loading = true }
        errorText = ""
        defer {
            if mine == generation {
                loading = false
                loadingMore = false
            }
        }
        do {
            let offset = append ? nextOffset : 0
            guard let url = URL(string: bridge.gifPickerUrl(query: pageQuery, offset: Int32(offset), stickers: stickersTab)) else {
                throw URLError(.badURL)
            }
            var request = URLRequest(url: url)
            request.timeoutInterval = 10
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                throw URLError(.badServerResponse)
            }
            let body = String(data: data, encoding: .utf8) ?? ""
            let fetched = GifChoiceItem.from(jsonArray: parseItems(body))
            let page = bridge.gifPickerPagination(responseJson: body, fetchedCount: Int32(fetched.count), requestedOffset: Int32(offset))
            guard mine == generation else { return }
            if append {
                let known = Set(items.map(\.idKey))
                items += fetched.filter { !known.contains($0.idKey) }
            } else {
                items = fetched
            }
            let pageMap = page
            nextOffset = intFrom(pageMap, "nextOffset")
            hasMore = pageMap["hasMore"] as? Bool ?? false
            if pageQuery.trimmingCharacters(in: .whitespaces).isEmpty {
                if stickersTab {
                    stickersSnapshot = items
                    stickersSavedAt = Date().timeIntervalSince1970 * 1000
                } else {
                    trendingSnapshot = items
                    trendingSavedAt = Date().timeIntervalSince1970 * 1000
                }
                persist()
            }
            if items.isEmpty && !pageQuery.trimmingCharacters(in: .whitespaces).isEmpty {
                errorText = "No GIFs matched \"\(pageQuery.trimmingCharacters(in: .whitespaces))\"."
            }
        } catch {
            if mine == generation {
                errorText = "Couldn't load GIFs. Check your connection."
            }
        }
    }

    private func parseItems(_ body: String) -> [[String: Any]] {
        guard let itemsJson = bridge.gifPickerParse(responseJson: body) as String?,
              let data = itemsJson.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return array
    }

    private func pick(_ gif: GifChoiceItem) {
        let recentJson = "[" + recent.map(\.itemJson).joined(separator: ",") + "]"
        let merged = bridge.gifPickerMergeRecent(recentJson: recentJson, pickJson: gif.itemJson)
        recent = itemsFromJsonString(merged)
        persist()
        onDismiss()
        onPick(gif)
    }

    private func itemsFromJsonString(_ json: String) -> [GifChoiceItem] {
        guard let data = json.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return GifChoiceItem.from(jsonArray: array)
    }
}

/** Preview tile: first frame while loading, broken-image fallback (legacy parity). */
private struct GifPreviewTile: View {
    let preview: String

    var body: some View {
        AsyncImage(url: URL(string: preview)) { phase in
            switch phase {
            case .success(let image):
                image.resizable().scaledToFill()
            case .failure:
                ZStack {
                    BitOSTheme.surfaceElevated
                    AppIcons.image(for: AppIcons.brokenImage)
                        .font(.system(size: 20))
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
            default:
                BitOSTheme.surfaceElevated
            }
        }
    }
}
