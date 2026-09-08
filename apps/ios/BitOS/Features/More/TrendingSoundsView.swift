import SwiftUI
import BusinessCore

/**
 * Trending sounds ("use this sound" Wave D, plan docs/product/
 * use-this-sound-plan.md §5; APP-021 bootstrap): counting `sound` tags
 * over the LIVE feed window IS the rank — no marketplace. Rows are
 * deterministic (shared `MemeSoundTrending.rank`, 3-day half-life);
 * "Use in Studio" re-attaches by URL through the Wave C editor path
 * (hash-verified download, no re-upload).
 */
struct TrendingSoundsView: View {
    let notes: [FeedNote]
    let onClose: () -> Void
    let onUseSound: (MemeSoundTrending.Row) -> Void

    @Environment(AppEnvironment.self) private var environment

    private var rows: [MemeSoundTrending.Row] {
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        let usages = notes.compactMap { note -> MemeSoundTrending.Usage? in
            guard let sound = note.soundUrl else { return nil }
            return MemeSoundTrending.Usage(
                eventId: note.id,
                createdAtMs: note.createdAt,
                url: sound,
                sha256: note.soundSha256 ?? "",
                sourceEventId: note.soundSourceEventId,
                authorPubkey: note.soundAuthorPubkey
            )
        }
        return Array(MemeSoundTrending.shared.rank(usages: usages, nowMs: now, maxRows: MemeSoundTrending.shared.MAX_ROWS))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Most-borrowed ♪ in your feed — ranked with a 3-day half-life.")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                if rows.isEmpty {
                    Section {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("No borrowed sounds yet")
                                .font(.subheadline.weight(.semibold))
                            Text("Open a video Bitz → Sound to borrow its audio, or publish a meme with a picked soundtrack — every borrow counts here.")
                                .font(.caption)
                                .foregroundStyle(BitOSTheme.textSecondary)
                        }
                        .padding(.vertical, 4)
                    }
                }
                Section {
                    ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                        HStack(spacing: BitOSTheme.Spacing.md) {
                            Image(systemName: "music.note")
                                .foregroundStyle(BitOSTheme.accent)
                                .frame(width: 34, height: 34)
                                .background(BitOSTheme.accentContainer)
                                .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Original sound")
                                    .font(.subheadline.weight(.semibold))
                                Text("\(row.uses) borrow\(row.uses == 1 ? "" : "s") · trending")
                                    .font(.caption)
                                    .foregroundStyle(BitOSTheme.textSecondary)
                            }
                            Spacer()
                            Button {
                                onUseSound(row)
                            } label: {
                                Text("Use in Studio")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(BitOSTheme.accent)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            .navigationTitle("Trending sounds")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: onClose)
                }
            }
        }
        .preferredColorScheme(BitOSTheme.preferredScheme)
    }
}
