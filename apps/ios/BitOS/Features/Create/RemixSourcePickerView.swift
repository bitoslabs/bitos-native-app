import SwiftUI
import PhotosUI
import CoreImage.CIFilterBuiltins
import BusinessCore

/// "Remix a Bitz" source picker (MEM-005a, plan docs/native/
/// create-hub-sound-remix-plan.md §Remix source picker; Android
/// `RemixSourcePickerScreen` parity): paste/scan a Bitz link or pick one
/// of the account's own recent verified Bitz, then the shared preflight
/// (creator, short id, media type, license, attribution, ask-required
/// warning) before the editor opens. A valid event with no loadable
/// media offers the seeded credited post — never a blank studio remix.
/// Cancel cancels the fetch and preserves the hub.
struct RemixSourcePickerView: View {
    let lookup: RemixSourceLookupStore
    let recent: RecentBitzStore
    let accountPubkey: String?
    let writeRelays: [String]
    let remixLineageCycles: (FeedNote) async -> Bool
    let onOpenRemix: (MemeRemixSeed) -> Void
    let onOpenCreditedPost: ([[String]]) -> Void
    let onClose: () -> Void

    @Environment(AppEnvironment.self) private var environment
    @State private var paste = ""
    @State private var pasteError: String?
    @State private var looking = false
    @State private var timedOut = false
    @State private var hit: RemixSourceHit?
    @State private var cycleBlocked = false
    @State private var scanItem: PhotosPickerItem?
    private let client = FrameworkBusinessCoreClient()

    private struct Preflight {
        let mediaUrl: String
        let mediaKind: String
        let license: String
        let askRequired: Bool
        let attribution: String
    }

    private func preflight(for hit: RemixSourceHit) -> Preflight? {
        let label = Self.shortKey(hit.note.pubkey)
        let tagsJson = client.tagsToJson(hit.event.tags)
        guard let data = client.remixSourcePreflight(
            eventId: hit.event.id,
            pubkey: hit.event.pubkey,
            kind: Int(hit.event.kind),
            createdAt: hit.event.createdAt,
            content: hit.event.content,
            tagsJson: tagsJson,
            creatorLabel: label
        ).data(using: .utf8),
            let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let idMatches = root["idMatches"] as? Bool,
            idMatches,
            let mediaUrl = root["mediaUrl"] as? String,
            let mediaKind = root["mediaKind"] as? String,
            let license = root["license"] as? String,
            let askRequired = root["askRequired"] as? Bool,
            let attribution = root["attribution"] as? String
        else { return nil }
        return Preflight(
            mediaUrl: mediaUrl, mediaKind: mediaKind, license: license,
            askRequired: askRequired, attribution: attribution
        )
    }

    /// Resolve one raw input (paste or decoded QR) through the lookup.
    private func resolve(_ raw: String) {
        if let message = RemixSourceLookupStore.rejectionMessage(raw, client: client) {
            pasteError = message
            return
        }
        pasteError = nil
        timedOut = false
        looking = true
        hit = nil
        Task {
            let resolved = await lookup.resolve(raw: raw)
            looking = false
            if let resolved {
                hit = resolved
            } else if case .timedOut = lookup.state {
                timedOut = true
            }
        }
    }

    /// Scan action: decode a QR from a picked photo (CoreImage); the
    /// decoded string flows through the SAME shared parser as a paste.
    /// Live-camera scanning rides the EDT-013 device pass.
    private func decodeScannedImage(_ data: Data) {
        let context = CIContext()
        let detector = CIDetector(
            ofType: CIDetectorTypeQRCode, context: context,
            options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]
        )
        guard let image = CIImage(data: data),
              let feature = detector?.features(in: image).first as? CIQRCodeFeature,
              let value = feature.messageString else {
            pasteError = "No Bitz link found in that image"
            return
        }
        paste = value
        resolve(value)
    }

    /// Open the editor — or the cycle block, or the credited-post fallback.
    private func openRemix(_ hit: RemixSourceHit, preflight: Preflight?) {
        Task {
            if await remixLineageCycles(hit.note) {
                cycleBlocked = true
                return
            }
            let label = Self.shortKey(hit.note.pubkey)
            lookup.cancel()
            if let preflight, !preflight.mediaUrl.isEmpty {
                onOpenRemix(MemeRemixSeed(
                    eventId: hit.note.id,
                    pubkey: hit.note.pubkey,
                    label: label,
                    relays: RemixRulesHelper.relayHints(
                        sourceRelays: hit.note.remixRelays, writeRelays: writeRelays
                    ),
                    mediaUrl: preflight.mediaUrl,
                    isVideo: hit.note.video != nil,
                    memeTag: hit.note.memeTag
                ))
            } else {
                onOpenCreditedPost(RemixRulesHelper.creditedPostTags(
                    note: hit.note, creatorLabel: label
                ))
            }
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
                HStack {
                    Text("Remix a Bitz")
                        .font(.title2.weight(.bold))
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button("Cancel") {
                        lookup.cancel()
                        onClose()
                    }
                    .accessibilityLabel("Close remix picker")
                }
                Text("Start from a Bitz; the original creator is credited automatically.")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textSecondary)

                // ── 1. Paste or scan a Bitz link (primary) ─────────────
                TextField("nostr:nevent1… or a BitOS note link", text: $paste)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onChange(of: paste) { _, value in
                        paste = String(value.prefix(2048))
                        pasteError = nil
                    }
                    .accessibilityLabel("Paste a Bitz link")
                if let pasteError {
                    Text(pasteError)
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.error)
                }
                HStack(spacing: BitOSTheme.Spacing.sm) {
                    Button {
                        resolve(paste)
                    } label: {
                        Text("Check this Bitz")
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                            .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.accent))
                            .foregroundStyle(.white)
                    }
                    .disabled(looking || paste.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityLabel("Check this Bitz")

                    PhotosPicker(selection: $scanItem, matching: .images) {
                        Label("Scan", systemImage: "photo")
                            .font(.subheadline.weight(.semibold))
                            .padding(.vertical, 8)
                            .padding(.horizontal, BitOSTheme.Spacing.sm)
                            .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).strokeBorder(BitOSTheme.border))
                            .foregroundStyle(BitOSTheme.textPrimary)
                    }
                    .accessibilityLabel("Scan a Bitz link")
                    .onChange(of: scanItem) { _, item in
                        guard let item else { return }
                        Task {
                            if let data = try? await item.loadTransferable(type: Data.self) {
                                decodeScannedImage(data)
                            } else {
                                pasteError = "No Bitz link found in that image"
                            }
                            scanItem = nil
                        }
                    }
                }

                // ── Named lookup stage ─────────────────────────────────
                if looking {
                    HStack(spacing: BitOSTheme.Spacing.sm) {
                        ProgressView()
                        Text("Checking this Bitz on relays…")
                            .font(.subheadline)
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                    .padding(BitOSTheme.Spacing.sm)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.surface))
                }
                if timedOut {
                    VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
                        Text("Relays did not answer in time")
                            .font(.subheadline.weight(.semibold))
                        Text("The Bitz may live on relays you are not connected to. Retry, or paste a different link.")
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.textSecondary)
                        Button("Retry") {
                            timedOut = false
                            resolve(paste)
                        }
                        .foregroundStyle(BitOSTheme.accent)
                    }
                    .padding(BitOSTheme.Spacing.sm)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.surface))
                }

                // ── Preflight card ────────────────────────────────────
                if let hit {
                    if let preflight = preflight(for: hit) {
                        preflightCard(hit: hit, preflight: preflight)
                    } else {
                        // Verification failed the id match — treat as a
                        // named lookup failure, never a silent pass.
                        Text("That Bitz could not be verified.")
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.error)
                    }
                }

                // ── 2. Your recent Bitz (account-scoped, verified) ─────
                Text("Your recent Bitz")
                    .font(.subheadline.weight(.semibold))
                let recentState = recent.state
                if recentState.isLoading {
                    HStack(spacing: BitOSTheme.Spacing.sm) {
                        ProgressView()
                        Text("Loading your recent Bitz…")
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                }
                if recentState.timedOut {
                    Text("Your relays did not answer — recent Bitz stay unavailable until retry.")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textTertiary)
                    Button("Retry") {
                        if let accountPubkey {
                            recent.open(pubkey: accountPubkey)
                        }
                    }
                    .foregroundStyle(BitOSTheme.accent)
                }
                if !recentState.isLoading && recentState.rows.isEmpty && !recentState.timedOut {
                    Text("None of your Bitz yet — publish one, or paste a link above.")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                ForEach(recentState.rows, id: \.note.id) { row in
                    Button {
                        hit = row
                    } label: {
                        HStack(spacing: BitOSTheme.Spacing.md) {
                            Group {
                                if let poster = row.note.video?.posterUrl,
                                   let url = URL(string: poster) {
                                    AsyncImage(url: url)
                                        .scaledToFill()
                                } else {
                                    AppIcons.image(for: AppIcons.play)
                                        .foregroundStyle(BitOSTheme.textTertiary)
                                }
                            }
                            .frame(width: 44, height: 56)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .background(RoundedRectangle(cornerRadius: 8).fill(BitOSTheme.surfaceOverlay))
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Your Bitz · \(mediaKindLabel(row.note))")
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(BitOSTheme.textPrimary)
                                Text("\(row.note.id.prefix(8))…")
                                    .font(.caption2)
                                    .foregroundStyle(BitOSTheme.textSecondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundStyle(BitOSTheme.textTertiary)
                        }
                        .padding(BitOSTheme.Spacing.sm)
                        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.surface))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Remix your Bitz \(row.note.id.prefix(8))")
                }
            }
            .padding(BitOSTheme.Spacing.base)
        }
        .background(BitOSTheme.background)
        .task(id: accountPubkey) {
            if let accountPubkey {
                recent.open(pubkey: accountPubkey)
            }
        }
        .onDisappear {
            lookup.cancel()
            recent.close()
        }
        .alert("This lineage loops", isPresented: $cycleBlocked) {
            Button("OK") { cycleBlocked = false }
        } message: {
            Text("Remixing this Bitz would create a remix cycle. Nothing was created — pick a different source.")
        }
    }

    @ViewBuilder
    private func preflightCard(hit: RemixSourceHit, preflight: Preflight) -> some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
            HStack(spacing: BitOSTheme.Spacing.md) {
                Group {
                    if let poster = hit.note.video?.posterUrl, let url = URL(string: poster) {
                        AsyncImage(url: url).scaledToFill()
                    } else {
                        AppIcons.image(for: AppIcons.photo)
                            .foregroundStyle(BitOSTheme.textTertiary)
                    }
                }
                .frame(width: 64, height: 80)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .background(RoundedRectangle(cornerRadius: 8).fill(BitOSTheme.surfaceOverlay))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Creator \(Self.shortKey(hit.note.pubkey))")
                        .font(.subheadline.weight(.semibold))
                    Text("Event \(hit.note.id.prefix(8))… · kind \(hit.note.kind)")
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.textSecondary)
                    if !preflight.mediaKind.isEmpty {
                        Text("Media: \(preflight.mediaKind)")
                            .font(.caption2)
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                    Text("License: \(preflight.license.isEmpty ? "none set (permissive)" : preflight.license)")
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
            }
            Text("Credit: \(preflight.attribution)")
                .font(.caption2)
                .foregroundStyle(BitOSTheme.textTertiary)
            if preflight.askRequired {
                Text("This Bitz carries a restrictive license (\(preflight.license)). Attribution is automatic; remixing may still need the creator's permission — it is not granted by opening the editor.")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .padding(BitOSTheme.Spacing.sm)
                    .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm).fill(BitOSTheme.surfaceOverlay))
            }
            if preflight.mediaUrl.isEmpty {
                Text("This Bitz has no loadable media, so it cannot open as a studio remix.")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textSecondary)
                Button {
                    openRemix(hit, preflight: preflight)
                } label: {
                    Text("Create credited post")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.accent))
                        .foregroundStyle(.white)
                }
                .accessibilityLabel("Create credited post")
            } else {
                Button {
                    openRemix(hit, preflight: preflight)
                } label: {
                    Text("Open remix")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.accent))
                        .foregroundStyle(.white)
                }
                .accessibilityLabel("Open remix")
            }
            Button("Cancel") {
                self.hit = nil
            }
            .foregroundStyle(BitOSTheme.textSecondary)
        }
        .padding(BitOSTheme.Spacing.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.surface))
    }

    private func mediaKindLabel(_ note: FeedNote) -> String {
        note.video != nil ? "video" : (note.mediaUrls.isEmpty ? "note" : "image")
    }

    static func shortKey(_ pubkey: String) -> String {
        guard pubkey.count >= 16 else { return pubkey.isEmpty ? "unknown" : pubkey }
        return "\(pubkey.prefix(8))…\(pubkey.suffix(4))"
    }
}

/// Thin Swift bridge to the shared RemixRules (relay hints + fallback
/// tags) — the rules stay single-sourced in shared core.
enum RemixRulesHelper {
    static func relayHints(sourceRelays: [String], writeRelays: [String]) -> [String] {
        let json = BusinessCoreBridge().remixRelayHintsJson(
            sourceRelaysJson: Self.jsonArray(sourceRelays),
            writeRelaysJson: Self.jsonArray(writeRelays)
        )
        return (try? JSONDecoder().decode([String].self, from: Data(json.utf8))) ?? []
    }

    static func creditedPostTags(note: FeedNote, creatorLabel: String) -> [[String]] {
        let bridge = BusinessCoreBridge()
        let baseJson = bridge.remixTagsJson(
            eventId: note.id,
            pubkey: note.pubkey,
            relaysJson: "[]"
        )
        var tags = (try? JSONDecoder().decode([[String]].self, from: Data(baseJson.utf8))) ?? []
        let attribution = (try? JSONDecoder().decode(
            [[String]].self, from: Data(bridge.remixAttributionTagJson(label: creatorLabel).utf8)
        )) ?? []
        tags.append(contentsOf: attribution)
        return tags
    }

    private static func jsonArray(_ values: [String]) -> String {
        "[" + values.map { value in
            "\"" + value.replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "\"", with: "\\\"") + "\""
        }.joined(separator: ",") + "]"
    }
}
