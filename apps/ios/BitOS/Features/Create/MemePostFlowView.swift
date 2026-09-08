import SwiftUI
import BusinessCore

/// Prototype publish flow (`#/create-details` → `#/create-review`): the
/// editor's "Next · post details" lands here; details collect the post
/// facts, preflight shows the REAL checklist and runs the existing
/// upload→sign→relay pipeline. Nothing is signed before the media upload
/// hash-verifies (repo safety rule) — the preflight copy says so.
struct MemePostFlowView: View {
    @Environment(\.dismiss) private var dismissFlow
    @Environment(AppEnvironment.self) private var environment
    @Bindable var store: MemeEditorStore
    let identity: IdentityStore
    let publisher: NotePublisher
    /** M4b remix lineage carried from the editor handoff, if any. */
    let remixSeed: MemeRemixSeed?
    let onPublished: () -> Void
    @State private var path: [FlowStep] = []

    init(
        store: MemeEditorStore,
        identity: IdentityStore,
        publisher: NotePublisher,
        remixSeed: MemeRemixSeed? = nil,
        onPublished: @escaping () -> Void
    ) {
        self.store = store
        self.identity = identity
        self.publisher = publisher
        self.remixSeed = remixSeed
        self.onPublished = onPublished
    }

    enum FlowStep: Hashable { case preflight, publishing, queue }

    var body: some View {
        NavigationStack(path: $path) {
            MemePostDetailsView(draft: $store.postDraft, store: store, onEditCover: { dismissFlow() }) {
                path.append(.preflight)
            }
            .navigationDestination(for: FlowStep.self) { step in
                switch step {
                case .preflight:
                    MemePreflightView(
                        store: store,
                        identity: identity,
                        draft: store.postDraft,
                        richTokens: { content in
                            ((environment.businessCore as? FrameworkBusinessCoreClient)?
                                .bridgeForFollowing() ?? BusinessCoreBridge())
                                .richTokens(content: content)
                        },
                        onSignAndPublish: {
                            beginPublish()
                            path.append(.publishing)
                        }
                    )
                case .publishing:
                    MemePublishingView(
                        store: store,
                        publisher: publisher,
                        onRetry: { beginPublish() },
                        onLater: { dismissFlow() },
                        onPublished: onPublished,
                        onOpenQueue: { path.append(.queue) }
                    )
                case .queue:
                    MemeRecoveryQueueView(
                        store: store,
                        identity: identity,
                        publisher: publisher,
                        onRetry: { job in
                            let bridge = (environment.businessCore as? FrameworkBusinessCoreClient)?
                                .bridgeForFollowing() ?? BusinessCoreBridge()
                            store.resumePublish(jobId: job, identity: identity, publisher: publisher, bridge: bridge)
                            path = [.publishing]
                        }
                    )
                }
            }
        }
        .preferredColorScheme(nil)
        .navigationBarBackButtonHidden(!path.isEmpty)
        // MST draft persistence: the draft lives in the editor session
        // (store), so a cover-edit round trip never loses the caption.
        // Seed a bitz remix lineage once, on first entry.
        .task {
            if store.postDraft.remixOf.isEmpty, let remixSeed {
                store.postDraft = MemePostDraft(remix: remixSeed)
            }
        }
    }

    /// Runs the REAL pipeline (render → hash-verified upload → sign →
    /// relay); the publishing screen's stepper tracks store.publishStep.
    private func beginPublish() {
        publisher.dismiss()
        let bridge = (environment.businessCore as? FrameworkBusinessCoreClient)?
            .bridgeForFollowing() ?? BusinessCoreBridge()
        let draft = store.postDraft
        RecentHashtagsStore.shared.record(
            used: Array(draft.captionHashtags) + draft.tags
        )
        store.publishActiveAsset(
            caption: draft.caption,
            altText: draft.altText,
            contentWarningReason: draft.contentWarningOn ? draft.contentWarningReason : nil,
            remixEventId: draft.remixOf,
            remixAuthor: draft.remixAuthor,
            remixRelays: draft.remixRelays,
            remixLabel: draft.remixLabel,
            license: draft.license.rawValue,
            extraTags: draft.extraTags,
            powBits: Int32(draft.powBits),
            identity: identity,
            publisher: publisher,
            bridge: bridge
        )
    }
}

/// The post facts the details screen collects (prototype CreateJob subset
/// that ships real protocol effects today; splits/PoW/schedule stay wave 4).
struct MemePostDraft {
    var caption = ""
    var altText = ""
    /// Explicit t-tags (beyond caption #hashtags), ≤ 8, deduped.
    var tags: [String] = []
    var audience: MemeAudience = .everyone
    var allowZaps = false
    var allowRemix = true
    var contentWarningOn = false
    var contentWarningReason = "Sensitive content"
    /// Shared `license` tag vocabulary (RemixRules.LICENSES).
    var license: MemeLicense = .cc0
    /// Optional remix lineage (MST-042): manual source event + author.
    var remixOf = ""
    var remixAuthor = ""
    /// Relay hints for the remix tag (source-tag relays + write relays, ≤3).
    var remixRelays: [String] = []
    /// Source author label — feeds the `attribution` credit on publish.
    var remixLabel = ""
    /// MST post-details PoW pick (NIP-13 difficulty, 0 = off) — mined
    /// after the media upload, before anything is signed.
    var powBits = 0

    /// M4b: a bitz handoff prefills the lineage and picks the web studio's
    /// remix default license (CC-BY-4.0) instead of CC0.
    init(remix seed: MemeRemixSeed? = nil) {
        guard let seed else { return }
        remixOf = seed.eventId
        remixAuthor = seed.pubkey
        remixRelays = seed.relays
        remixLabel = seed.label
        license = .ccBy
    }

    /// Caption #hashtags (the composer already derives these itself).
    var captionHashtags: Set<String> {
        var found = Set<String>()
        for word in caption.split(whereSeparator: { $0.isWhitespace }) where word.hasPrefix("#") {
            let tag = word.dropFirst().lowercased()
            if !tag.isEmpty { found.insert(String(tag)) }
        }
        return found
    }

    /// Extra event tags for the publish call: explicit t-tags the caption
    /// doesn't already carry, the license tag, and — when Zap settings is
    /// off — the shared advisory `bitz:zaps` marker our cards honor.
    var extraTags: [[String]] {
        var seen = captionHashtags
        var tags: [[String]] = []
        for tag in self.tags where !seen.contains(tag) {
            seen.insert(tag)
            tags.append(["t", tag])
        }
        tags.append(["license", license.rawValue])
        if !allowZaps { tags.append(["bitz:zaps", "off"]) }
        return tags
    }
}

enum MemeAudience: String, CaseIterable {
    case everyone, followers, dm

    var label: String {
        switch self {
        case .everyone: return "Everyone"
        case .followers: return "Followers"
        case .dm: return "You + DMs"
        }
    }

    var next: MemeAudience {
        switch self {
        case .everyone: return .followers
        case .followers: return .dm
        case .dm: return .everyone
        }
    }
}

enum MemeLicense: String, CaseIterable {
    case cc0 = "CC0-1.0"
    case ccBy = "CC-BY-4.0"
    case nostrOnly = "bitz/all-reserved"

    var label: String {
        switch self {
        case .cc0: return "CC0 (public)"
        case .ccBy: return "CC-BY"
        case .nostrOnly: return "Nostr only"
        }
    }

    var note: String {
        switch self {
        case .cc0:
            return "CC0 — anyone can remix, reuse and commercialize. Maximum spread, maximum remixes."
        case .ccBy:
            return "CC-BY — reuse allowed with attribution. The remix chain keeps your npub attached."
        case .nostrOnly:
            return "Nostr only — relays may mirror, but the license tag asks apps to block external reuploads."
        }
    }
}

// MARK: - Post details (prototype `#/create-details`)

private struct MemePostDetailsView: View {
    @Binding var draft: MemePostDraft
    let store: MemeEditorStore
    /// Cover "Edit" — drop the flow and land back on the editor stage,
    /// where the video scrub row's "Set cover" lives.
    let onEditCover: () -> Void
    let onReview: () -> Void
    @State private var tagInput = ""
    /// Last remixable license the chips held, so the Allow-remix switch can
    /// restore it after "Nostr only" (the switch and the chips are two views
    /// of the same `license` tag).
    @State private var lastRemixableLicense: MemeLicense = .cc0

    private static let maxTags = 8

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
                previewRow
                tagsCard
                settingsCard
                licenseSection
                waveFourNote
                Button {
                    onReview()
                } label: {
                    Text("Review preflight")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(BitOSTheme.accent)
            }
            .padding(.horizontal, BitOSTheme.Spacing.lg)
            .padding(.vertical, BitOSTheme.Spacing.md)
        }
        .navigationTitle("Post details")
        .navigationBarTitleDisplayMode(.inline)
        .background(BitOSTheme.background)
        .onChange(of: draft.license) { _, new in
            if new != .nostrOnly { lastRemixableLicense = new }
        }
    }

    // ── Preview + caption ─────────────────────────────────────────────

    private var previewRow: some View {
        HStack(alignment: .top, spacing: BitOSTheme.Spacing.md) {
            MemePostPreviewThumb(store: store)
                .frame(width: 80, height: 112)
            VStack(alignment: .trailing, spacing: 4) {
                // Flat input (user-directed): no border, no card padding —
                // the field's own hit spacing only.
                BitosField("Write a caption… #tag @mention", text: $draft.caption, axis: .vertical)
                    .lineLimit(4, reservesSpace: true)
                    .padding(.vertical, 2)
                    .onChange(of: draft.caption) { _, value in
                        if value.count > 300 { draft.caption = String(value.prefix(300)) }
                    }
                Text("\(draft.caption.count) / 300")
                    .font(.caption2)
                    .foregroundStyle(
                        draft.caption.count > 300 ? BitOSTheme.warning : BitOSTheme.textSecondary
                    )
            }
        }
    }

    // ── Tags (nostr t-tags) ───────────────────────────────────────────
    private var tagsCard: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            Text("Tags · nostr t-tags")
                .font(.caption.weight(.semibold))
                .foregroundStyle(BitOSTheme.textSecondary)
            if draft.tags.isEmpty {
                Text("No tags yet — type below")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textSecondary.opacity(0.7))
            } else {
                FlowTagRow(
                    tags: draft.tags,
                    onRemove: { tag in draft.tags.removeAll { $0 == tag } }
                )
            }
            HStack(spacing: BitOSTheme.Spacing.xs) {
                Image(systemName: "number")
                    .font(.system(size: 13))
                    .foregroundStyle(BitOSTheme.textSecondary)
                BitosField("Add tag and press space", text: $tagInput)
                    .font(.subheadline)
                    .onSubmit(commitTag)
                    .onChange(of: tagInput) { _, value in
                        if value.hasSuffix(" ") { commitTag() }
                    }
            }
            .padding(.top, 4)
            .overlay(alignment: .top) { Divider() }
            recentHashtagChips
        }
        .padding(BitOSTheme.Spacing.md)
        .background(BitOSTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))
    }

    private func commitTag() {
        let tag = tagInput
            .trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: "#", with: "")
            .lowercased()
        tagInput = ""
        addTag(tag)
    }

    private func addTag(_ tag: String) {
        guard !tag.isEmpty, !draft.tags.contains(tag),
              draft.tags.count < Self.maxTags else { return }
        draft.tags.append(tag)
    }

    /// Recently used hashtags — one-tap reuse (shared `RecentHashtags`
    /// ledger recorded on publish). Tags this post already carries drop out.
    private var recentHashtagChips: some View {
        let exclude = draft.tags + Array(draft.captionHashtags)
        let recent = RecentHashtagsStore.shared.suggestions(exclude: exclude)
        return Group {
            if !recent.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(recent, id: \.self) { tag in
                            Button("#\(tag)") { addTag(tag) }
                                .font(.caption.weight(.semibold))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(Capsule().fill(BitOSTheme.background))
                                .overlay(Capsule().strokeBorder(BitOSTheme.border))
                                .foregroundStyle(BitOSTheme.accent)
                                .buttonStyle(.plain)
                        }
                    }
                    .padding(.top, 4)
                }
            }
        }
    }

    // ── Settings rows ─────────────────────────────────────────────────

    private var settingsCard: some View {
        VStack(spacing: 0) {
            if store.isVideoMode {
                HStack {
                    DetailRow(
                        icon: "photo",
                        title: "Cover image",
                        subtitle: store.coverThumbUrl != nil
                            ? "custom frame captured"
                            : "first frame (capture on the editor stage)"
                    )
                    Button("Edit") { onEditCover() }
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(BitOSTheme.accent)
                        .buttonStyle(.plain)
                }
                .padding(.vertical, BitOSTheme.Spacing.xs)
                Divider().padding(.leading, 48)
                // MST-036: quality/size + live MB estimate right on the
                // post screen — fix an over-cap pick without a round trip.
                ExportPresetPicker(store: store)
                    .padding(.vertical, BitOSTheme.Spacing.xs)
                Divider().padding(.leading, 48)
            }
            DetailRow(icon: "globe", title: "Who can watch",
                subtitle: "Published publicly on Nostr", trailing: .value("Everyone"))
            Divider().padding(.leading, 48)
            Toggle(isOn: $draft.allowZaps) {
                DetailRow(icon: "bolt.fill", tint: BitOSTheme.warning, title: "Zap settings",
                    subtitle: "viewers can zap this post — off hides the zap action (advisory tag)")
            }
            .padding(.vertical, 2)
            Divider().padding(.leading, 48)
            Toggle(isOn: allowRemixBinding) {
                DetailRow(
                    icon: "arrow.triangle.2.circlepath",
                    title: "Allow remix",
                    subtitle: "others duet / remix with attribution — rides the license tag"
                )
            }
            .padding(.vertical, 2)
            Divider().padding(.leading, 48)
            Toggle(isOn: $draft.contentWarningOn) {
                DetailRow(
                    icon: "eye.slash",
                    title: "Content warning",
                    subtitle: "gate the post behind a visible warning"
                )
            }
            .padding(.vertical, 2)
            Divider().padding(.leading, 48)
            // MST post-details PoW (NIP-13): the RANK UI reuses the shared
            // PowCard pieces — PowDifficultySelector (slider + hash bars) +
            // PowBadge. Mined AFTER the media upload (the imeta must be
            // final) and BEFORE anything is signed.
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
                HStack {
                    DetailRow(
                        icon: "bolt.fill",
                        tint: BitOSTheme.warning,
                        title: "Proof of work",
                        subtitle: "anti-spam difficulty (NIP-13) — mined after upload, before signing"
                    )
                    if draft.powBits > 0 {
                        PowBadge(difficulty: draft.powBits)
                    }
                }
                PowDifficultySelector(target: $draft.powBits)
            }
            .padding(.vertical, 6)
            if !draft.remixOf.isEmpty {
                Divider().padding(.leading, 48)
                remixLineagePreview
            }
            Divider().padding(.leading, 48)
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
                Text("Alt text (optional)")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(BitOSTheme.textSecondary)
                BitosField("Describe the meme (defaults to the caption)", text: $draft.altText, size: .small)
                    .font(.caption2)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }
            .padding(.vertical, 6)
        }
        .padding(BitOSTheme.Spacing.sm)
        .background(BitOSTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))
    }

    /// Allow-remix and the license chips are the same `license` tag seen two
    /// ways: off is exactly "Nostr only" (bitz/all-reserved), on restores the
    /// last remixable code the chips held.
    private var allowRemixBinding: Binding<Bool> {
        Binding(
            get: { draft.license != .nostrOnly },
            set: { allow in
                if allow {
                    draft.license = lastRemixableLicense
                } else {
                    if draft.license != .nostrOnly { lastRemixableLicense = draft.license }
                    draft.license = .nostrOnly
                }
            }
        )
    }

    /// MST-042 lineage is stamped by the machine (remix + p + meme tags via
    /// the shared `RemixRules` seam) — never typed by hand. When a remix
    /// source rides the draft, preview it read-only with the license note.
    private var remixLineagePreview: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("Remix · auto-attributed")
                .font(.caption.weight(.semibold))
                .foregroundStyle(BitOSTheme.textSecondary)
            Text("source \(shortRef(draft.remixOf))")
                .font(.caption2.monospaced())
                .foregroundStyle(BitOSTheme.textSecondary)
            if !draft.remixAuthor.isEmpty {
                Text("author \(shortRef(draft.remixAuthor))")
                    .font(.caption2.monospaced())
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            Text("remix + p tags are stamped on publish — keep a remixable license (CC0 / CC-BY) so the chain stays open")
                .font(.caption2)
                .foregroundStyle(BitOSTheme.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 6)
    }

    /// Relay-friendly short form of an event id / npub for read-only rows.
    private func shortRef(_ value: String) -> String {
        value.count > 16 ? "\(value.prefix(8))…\(value.suffix(4))" : value
    }

    /// Prototype splits/PoW/schedule slot: the tags exist on the wire
    /// (NIP-57 zap splits, NIP-13 PoW, NIP-38 schedule) but the pipeline
    /// doesn't mine or schedule yet — say so instead of faking controls.
    private var waveFourNote: some View {
        HStack(alignment: .top, spacing: BitOSTheme.Spacing.xs) {
            Image(systemName: "info.circle")
                .font(.caption)
                .foregroundStyle(BitOSTheme.textSecondary)
            Text("Split payments (NIP-57 zap tags), proof-of-work and scheduled publishing ship in wave 4 — this pipeline won't fake them.")
                .font(.caption2)
                .foregroundStyle(BitOSTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(BitOSTheme.Spacing.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(BitOSTheme.surface.opacity(0.6))
        .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))
    }

    // ── License ───────────────────────────────────────────────────────

    private var licenseSection: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            Text("License · imeta license tag")
                .font(.caption.weight(.semibold))
                .foregroundStyle(BitOSTheme.textSecondary)
            HStack(spacing: BitOSTheme.Spacing.xs) {
                ForEach(MemeLicense.allCases, id: \.rawValue) { license in
                    Button {
                        draft.license = license
                    } label: {
                        Text(license.label)
                            .font(.caption.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                            .background(
                                draft.license == license
                                    ? BitOSTheme.accent.opacity(0.18)
                                    : Color.clear
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .strokeBorder(
                                        draft.license == license ? BitOSTheme.accent : BitOSTheme.border
                                    )
                            )
                            .foregroundStyle(
                                draft.license == license ? BitOSTheme.accent : BitOSTheme.textSecondary
                            )
                    }
                }
            }
            Text(draft.license.note)
                .font(.caption2)
                .foregroundStyle(BitOSTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - Preflight (prototype `#/create-review`)

private struct MemePreflightView: View {
    @Environment(\.dismiss) private var dismissFlow
    let store: MemeEditorStore
    let identity: IdentityStore
    let draft: MemePostDraft
    /// NIP-27 rich tokens for the caption preview (bridge seam — the same
    /// tokenizer the feed cards render with).
    let richTokens: (String) -> String
    let onSignAndPublish: () -> Void
    @State private var captionRichJson = "[]"

    private var busy: Bool {
        store.publishState == .uploading || store.publishState == .publishing
    }

    /// Kind + media facts for the summary card.
    private var mediaSummary: String {
        if store.isVideoMode {
            return "Video · \(clock(store.timelineDurationMs)) · \(store.clips.count) clip\(store.clips.count == 1 ? "" : "s")"
        }
        if store.isGifMode {
            return "GIF · \(store.gifFramesCount) frames"
        }
        return "Image meme"
    }

    private func clock(_ ms: Int64) -> String {
        String(format: "%d:%02d", Int(ms / 1000) / 60, Int(ms / 1000) % 60)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
                // Summary card: thumb + kind + imeta facts, with the caption
                // rendered under the facts exactly as the feed card will
                // show it.
                HStack(alignment: .top, spacing: BitOSTheme.Spacing.md) {
                    MemePostPreviewThumb(store: store)
                        .frame(width: 64, height: 96)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(mediaSummary)
                            .font(.subheadline.weight(.bold))
                        Text("kind \(kindLabel) · imeta dims + sha256 pinned at upload\(draft.contentWarningOn ? " · CW on" : "")")
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.textSecondary)
                        if !draft.caption.isEmpty {
                            ExpandableRichText(json: captionRichJson)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .padding(BitOSTheme.Spacing.md)
                .background(BitOSTheme.surface)
                .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))
                .task(id: draft.caption) {
                    captionRichJson = richTokens(draft.caption)
                }

                VStack(spacing: 0) {
                    PreflightLine(done: true, label: "Media ready", meta: mediaSummary)
                    Divider().padding(.leading, 32)
                    PreflightLine(
                        done: true,
                        label: "Caption + tags",
                        meta: "\(draft.caption.isEmpty ? "(none)" : "✓") · \(draft.captionHashtags.count + draft.tags.count) t-tags"
                    )
                    Divider().padding(.leading, 32)
                    PreflightLine(
                        done: !draft.altText.isEmpty || !draft.caption.isEmpty,
                        label: "Alt text",
                        meta: draft.altText.isEmpty ? "defaults to caption" : "✓ set"
                    )
                    Divider().padding(.leading, 32)
                    PreflightLine(
                        done: !draft.contentWarningOn || !draft.contentWarningReason.isEmpty,
                        label: "Content warning",
                        meta: draft.contentWarningOn ? "gated: \(draft.contentWarningReason)" : "off"
                    )
                    Divider().padding(.leading, 32)
                    PreflightLine(
                        done: true,
                        label: "License",
                        meta: "\(draft.license.label) · license tag"
                    )
                    Divider().padding(.leading, 32)
                    PreflightLine(
                        done: true,
                        label: "Zaps",
                        meta: draft.allowZaps ? "on · viewers can zap" : "off · advisory bitz:zaps tag"
                    )
                    Divider().padding(.leading, 32)
                    PreflightLine(done: true, label: "Audience", meta: "Everyone · public post")
                    Divider().padding(.leading, 32)
                    PreflightLine(
                        done: true,
                        label: "Relays",
                        meta: "\(DefaultRelays.writeUrls.count) write relays · receipt machine"
                    )
                    Divider().padding(.leading, 32)
                    PreflightLine(
                        done: identity.account != nil,
                        label: "Signer",
                        meta: identity.account != nil ? "device key ready" : "import an identity first (Profile tab)"
                    )
                }
                .padding(BitOSTheme.Spacing.sm)
                .background(BitOSTheme.surface)
                .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))

                if let failure = store.publishFailure {
                    Text(failure)
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.error)
                }
                switch store.publishState {
                case .uploading, .publishing:
                    HStack(spacing: 8) {
                        ProgressView()
                        Text(store.publishState == .uploading
                             ? "Uploading + hash-verifying media…"
                             : "Signing + publishing to relays…")
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                case .done:
                    HStack(spacing: 6) {
                        AppIcons.image(for: AppIcons.checkCircle)
                        Text("Published — nothing was signed before the hash check ✓")
                    }
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(BitOSTheme.success)
                case .idle:
                    EmptyView()
                }

                HStack(spacing: BitOSTheme.Spacing.sm) {
                    Button {
                        dismissFlow()
                    } label: {
                        Text("Edit")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(busy)
                    publishButton
                }
                Text("Order is fixed by protocol: media uploads & hash-verifies before anything is signed.")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            .padding(BitOSTheme.Spacing.md)
        }
        .navigationTitle("Preflight")
        .navigationBarTitleDisplayMode(.inline)
        .background(BitOSTheme.background)
    }

    private var kindLabel: String {
        if store.isVideoMode { return "22/21 by orientation" }
        if store.isGifMode { return "20 · image/gif" }
        return "20 · picture"
    }

    private var publishButton: some View {
        Button {
            onSignAndPublish()
        } label: {
            Text("Sign & publish")
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .tint(BitOSTheme.accent)
        .disabled(busy || identity.account == nil)
    }
}

// MARK: - Publishing machine (prototype `#/publishing`)

/// The publish machine: 8 REAL pipeline stages as a stepper with a
/// progress bar, the per-attempt job chip, failure recovery (Retry /
/// Later) and the confirmed-event result. Rows track
/// `store.publishStep` — set at actual checkpoints, never simulated.
private struct MemePublishingView: View {
    let store: MemeEditorStore
    let publisher: NotePublisher
    let onRetry: () -> Void
    let onLater: () -> Void
    let onPublished: () -> Void
    var onOpenQueue: () -> Void = {}

    private var publisherFailed: Bool {
        switch publisher.result {
        case .rejected, .timeout, .signingRefused, .invalid: return true
        default: return false
        }
    }

    private var succeeded: Bool { publisher.result == .published }
    private var failed: Bool { store.publishFailure != nil || publisherFailed }
    private var running: Bool {
        store.publishState == .uploading || store.publishState == .publishing
    }

    private var currentStep: MemeEditorStore.PublishMachineStep {
        store.publishStep ?? .confirm
    }

    private var doneCount: Double {
        if succeeded { return Double(MemeEditorStore.PublishMachineStep.allCases.count) }
        return Double(currentStep.rawValue)
    }

    /// The in-flight step's REAL fraction (render encoder %, upload socket
    /// bytes) — only while that step is actually current, so a stale value
    /// never leaks onto another row.
    private var stageFraction: Double? {
        guard running, !failed, !succeeded, store.publishStageProgress != nil else { return nil }
        return store.publishStageProgress
    }

    private func rowState(_ index: Int) -> MemePublishingViewRowState {
        if succeeded { return .done }
        let current = currentStep.rawValue
        if failed {
            if index < current { return .done }
            if index == current { return .failed }
            return .pending
        }
        if index < current { return .done }
        if index == current { return .current }
        return .pending
    }

    /// Mode-specific facts for the render row + summary.
    private var renderDetail: String {
        if store.isVideoMode {
            return "MP4 · \(monoClock(store.timelineDurationMs)) · \(store.clips.count) clip\(store.clips.count == 1 ? "" : "s")"
        }
        if store.isGifMode {
            return "GIF · \(store.gifFramesCount) frames"
        }
        if let asset = store.activeAsset {
            let width = Int((asset.image.size.width * asset.image.scale).rounded())
            let height = Int((asset.image.size.height * asset.image.scale).rounded())
            return "PNG · \(width)×\(height)"
        }
        return "final media"
    }

    private var kindDetail: String {
        if store.isVideoMode { return "kind 22/21 · imeta + tags" }
        if store.isGifMode { return "kind 20 · imeta (image/gif)" }
        return "kind 20 · imeta + tags"
    }

    private var confirmDetail: String {
        let accepted = publisher.receipts.filter { $0.accepted == true }.map(\.relayHost)
        if !accepted.isEmpty {
            return "OK from " + accepted.prefix(3).joined(separator: ", ")
        }
        return "\(DefaultRelays.writeUrls.count) write relays · awaiting first OK"
    }

    private var rows: [(String, String)] {
        [
            ("Render & encode", renderDetail),
            ("Content hash", "SHA-256 over the rendered bytes"),
            // Videos route BitOS-first (canonical) with a Blossom replica;
            // the row must say where bytes actually go, not just "Blossom".
            (store.isVideoMode ? "Upload media" : "Upload to Blossom",
             store.isVideoMode ? "BitOS API (canonical) + Blossom replica" : "blossom.primal.net · authed PUT"),
            ("Verify hash", "server hash must match the local one"),
            ("Build event", kindDetail),
            ("Sign", "key never leaves the device"),
            ("Publish to relays", "\(DefaultRelays.writeUrls.count) write relays"),
            ("Relay confirms", confirmDetail),
        ]
    }

    private func monoClock(_ ms: Int64) -> String {
        String(format: "%d:%02d", Int(ms / 1000) / 60, Int(ms / 1000) % 60)
    }

    private var eventIdLabel: String? {
        guard let id = store.publishEventId ?? publisher.inFlightId, id.count >= 12 else { return nil }
        return "\(id.prefix(8))…\(id.suffix(4))"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
                HStack {
                    Text(succeeded ? "Published" : failed ? "Publish stalled" : "Publishing…")
                        .font(.headline)
                    Spacer()
                    Text("job \(store.publishJobId)")
                        .font(.system(size: 10, weight: .semibold, design: .monospaced))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(BitOSTheme.surface))
                        .overlay(Capsule().strokeBorder(BitOSTheme.border))
                        .foregroundStyle(BitOSTheme.textSecondary)
                }

                // At-a-glance ring: percent of completed stages + the
                // current stage's REAL intra fraction (render encoder %,
                // upload socket bytes) absorbed into the overall percent,
                // and the in-flight stage's sweep arc for liveness on the
                // steps that cannot report one.
                PublishProgressRing(
                    fraction: (doneCount + (stageFraction ?? 0)) / 8,
                    percent: Int(((doneCount + (stageFraction ?? 0)) / 8 * 100).rounded()),
                    stepIndex: currentStep.rawValue,
                    stepCount: MemeEditorStore.PublishMachineStep.allCases.count,
                    stepLabel: rows[min(rows.count - 1, max(0, currentStep.rawValue))].0 +
                        (running ? (store.publishStageDetail.map { " · \($0)" } ?? "") : ""),
                    running: running,
                    failed: failed,
                    succeeded: succeeded
                )

                VStack(spacing: 0) {
                    ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                        MachineRowView(
                            number: index + 1,
                            label: row.0,
                            detail: index == 7 ? confirmDetail : row.1,
                            state: rowState(index),
                            liveDetail: rowState(index) == .current ? store.publishStageDetail : nil,
                            liveFraction: rowState(index) == .current ? stageFraction : nil
                        )
                        if index < rows.count - 1 {
                            Divider().padding(.leading, 32)
                        }
                    }
                }
                .padding(BitOSTheme.Spacing.sm)
                .background(BitOSTheme.surface)
                .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))

                if failed {
                    HStack(alignment: .top, spacing: BitOSTheme.Spacing.xs) {
                        AppIcons.image(for: AppIcons.close)
                            .font(.system(size: 13, weight: .bold))
                        Text(failureText)
                            .font(.caption)
                    }
                    .foregroundStyle(BitOSTheme.error)
                    .padding(BitOSTheme.Spacing.sm)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(BitOSTheme.error.opacity(0.1))
                    .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))

                    Text("The job is recoverable — nothing was signed before the hash check, so retrying never double-publishes media.")
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.textSecondary)

                    HStack(spacing: BitOSTheme.Spacing.sm) {
                        Button {
                            onLater()
                        } label: {
                            Text("Later")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        Button {
                            onRetry()
                        } label: {
                            Text("Retry now")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(BitOSTheme.accent)
                        .disabled(running)
                    }
                    Button("Recovery queue", action: onOpenQueue)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(BitOSTheme.accent)
                        .frame(maxWidth: .infinity)
                }

                if succeeded {
                    HStack(alignment: .top, spacing: BitOSTheme.Spacing.xs) {
                        AppIcons.image(for: AppIcons.checkCircle)
                            .font(.system(size: 13))
                        Text(successText)
                            .font(.caption.weight(.semibold))
                    }
                    .foregroundStyle(BitOSTheme.success)
                    .padding(BitOSTheme.Spacing.sm)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(BitOSTheme.success.opacity(0.1))
                    .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))

                    HStack(spacing: BitOSTheme.Spacing.sm) {
                        Button("Recovery queue", action: onOpenQueue)
                            .frame(maxWidth: .infinity)
                            .buttonStyle(.bordered)
                        Button {
                            onPublished()
                        } label: {
                            Text("View on Bitz")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(BitOSTheme.accent)
                    }
                }

                if running {
                    Text("Order is fixed by protocol: media uploads & hash-verifies before anything is signed.")
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
            }
            .padding(BitOSTheme.Spacing.md)
        }
        .navigationTitle("Publishing")
        .navigationBarTitleDisplayMode(.inline)
        .background(BitOSTheme.background)
    }

    private var failureText: String {
        if let failure = store.publishFailure { return failure }
        switch publisher.result {
        case .rejected(let detail): return "A relay rejected the event\(detail.map { " — \($0)" } ?? "")."
        case .timeout: return "No relay confirmed within the window — the event may still land; retry is safe."
        case .signingRefused: return "Signing refused — import an identity first (Profile tab)."
        case .invalid: return "The event could not be composed."
        default: return "Publish failed."
        }
    }

    private var successText: String {
        let accepted = publisher.receipts.filter { $0.accepted == true }.count
        let id = eventIdLabel.map { "event \($0) " } ?? ""
        return "Published — \(id)confirmed on \(accepted) relay\(accepted == 1 ? "" : "s")."
    }
}

/// Determinate ring showing the percent of COMPLETED machine stages
/// (checkpoints only — the fraction never simulates), a rotating sweep
/// arc for liveness while a stage is in flight, and the step counter +
/// current stage name beside it. Success fills green with a check;
/// failure freezes at the stalled fraction in error red.
private struct PublishProgressRing: View {
    let fraction: Double
    let percent: Int
    /// 0-based index of the current (or stalled) stage.
    let stepIndex: Int
    let stepCount: Int
    let stepLabel: String
    let running: Bool
    let failed: Bool
    let succeeded: Bool

    private var caption: String {
        if succeeded { return "All \(stepCount) stages complete" }
        if failed { return "Stalled at step \(stepIndex + 1) of \(stepCount)" }
        return "Step \(stepIndex + 1) of \(stepCount)"
    }

    private var subtitle: String {
        if succeeded { return "Event confirmed by relay receipt" }
        return stepLabel
    }

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.md) {
            ZStack {
                Circle()
                    .stroke(BitOSTheme.surface, lineWidth: 6)
                Circle()
                    .trim(from: 0, to: CGFloat(max(0, min(1, fraction))))
                    .stroke(
                        failed ? BitOSTheme.error : succeeded ? BitOSTheme.success : BitOSTheme.accent,
                        style: StrokeStyle(lineWidth: 6, lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))
                    .animation(.easeInOut(duration: 0.3), value: fraction)
                // Sweep arc: runs ahead of the frozen fraction so long
                // single stages (video encode, big upload) read as alive.
                // Own view = own state, so a retry after a terminal state
                // restarts the rotation instead of inheriting 360°.
                if running {
                    PublishSweepArc()
                }
                Group {
                    if succeeded {
                        AppIcons.image(for: AppIcons.checkCircle)
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundStyle(BitOSTheme.success)
                    } else if failed {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(BitOSTheme.error)
                    } else {
                        Text("\(percent)%")
                            .font(.system(size: 15, weight: .bold, design: .rounded))
                            .foregroundStyle(BitOSTheme.textPrimary)
                            .monospacedDigit()
                    }
                }
            }
            .frame(width: 68, height: 68)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(
                succeeded ? "Publish complete" :
                    failed ? "Publish stalled at \(stepLabel), \(percent) percent done" :
                    "\(percent) percent done, current step \(stepLabel)"
            )

            VStack(alignment: .leading, spacing: 2) {
                Text(caption)
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .foregroundStyle(failed ? BitOSTheme.error : succeeded ? BitOSTheme.success : BitOSTheme.accent)
                Text(subtitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(BitOSTheme.textPrimary)
            }
            Spacer(minLength: 0)
        }
        .padding(BitOSTheme.Spacing.sm)
        .background(BitOSTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))
    }
}

/// The rotating arc orbiting the ring while a stage is in flight. Its
/// `@State` is scoped to its own appearance: a retry re-inserts the view
/// and restarts the sweep from 0°.
private struct PublishSweepArc: View {
    @State private var angle = 0.0

    var body: some View {
        Circle()
            .trim(from: 0.94, to: 1)
            .stroke(
                BitOSTheme.accent.opacity(0.85),
                style: StrokeStyle(lineWidth: 6, lineCap: .round)
            )
            .rotationEffect(.degrees(-90 + angle))
            .onAppear {
                withAnimation(.linear(duration: 1.1).repeatForever(autoreverses: false)) {
                    angle = 360
                }
            }
    }
}

/// One stepper row: numbered dot (✓ done · spinner current · ✗ failed) +
/// label + mono detail. The current row can carry its own REAL fraction
/// (render encoder %, upload socket bytes) as a live detail line + thin
/// sub-bar.
private struct MachineRowView: View {
    let number: Int
    let label: String
    let detail: String
    let state: MemePublishingViewRowState
    /// Live byte/encoder truth for the in-flight stage (nil otherwise).
    var liveDetail: String? = nil
    var liveFraction: Double? = nil

    var body: some View {
        HStack(alignment: .top, spacing: BitOSTheme.Spacing.sm) {
            ZStack {
                Circle()
                    .strokeBorder(
                        state == .current ? BitOSTheme.accent :
                            state == .failed ? BitOSTheme.error : BitOSTheme.border,
                        lineWidth: state == .current ? 2 : 1
                    )
                    .frame(width: 22, height: 22)
                switch state {
                case .done:
                    AppIcons.image(for: AppIcons.checkCircle)
                        .font(.system(size: 13))
                        .foregroundStyle(BitOSTheme.success)
                case .failed:
                    Image(systemName: "xmark")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(BitOSTheme.error)
                case .current:
                    // The in-flight stage gets a live spinner, not a static
                    // number — a stage can legitimately run for a long time
                    // (video encode, large upload) between checkpoints.
                    ProgressView()
                        .controlSize(.small)
                        .tint(BitOSTheme.accent)
                case .pending:
                    Text("\(number)")
                        .font(.system(size: 10, weight: .semibold, design: .monospaced))
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(state == .pending ? BitOSTheme.textSecondary : BitOSTheme.textPrimary)
                Text(liveDetail ?? detail)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(BitOSTheme.textSecondary)
                if let fraction = liveFraction {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(BitOSTheme.border.opacity(0.35))
                            Capsule()
                                .fill(BitOSTheme.accent)
                                .frame(width: max(3, geo.size.width * fraction))
                        }
                    }
                    .frame(height: 3)
                    .padding(.top, 2)
                    .animation(.easeInOut(duration: 0.2), value: fraction)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(label), \(state == .done ? "done" : state == .failed ? "failed" : state == .current ? "in progress" : "pending")")
    }
}

// MARK: - Recovery queue (prototype `#/queue`)

/// Durable publish-job recovery: every non-terminal attempt from the
/// ledger with its stage, the REAL integrity check (stored bytes vs the
/// recorded digest), retry-from-media and discard.
private struct MemeRecoveryQueueView: View {
    let store: MemeEditorStore
    let identity: IdentityStore
    let publisher: NotePublisher
    let onRetry: (Int) -> Void
    @State private var verifyResults: [Int: String] = [:]
    @State private var discardTarget: MemePublishJob?

    private static let stageNames = [
        "render & encode", "content hash", "upload to Blossom", "verify hash",
        "build event", "sign", "publish to relays", "relay confirms",
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
                let jobs = store.jobStore.recoverable
                if jobs.isEmpty {
                    Text("Nothing to recover — every publish finished or was discarded. Killing the app mid-publish is safe: the attempt lands here and resumes from the stored media.")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .padding(BitOSTheme.Spacing.md)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(BitOSTheme.surface)
                        .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))
                }
                ForEach(jobs) { job in
                    jobCard(job)
                }
                Text("Every background job is durable, idempotent and cancellable. Retries re-run from the stored media — the upload dedupes by content hash, and nothing signs before that hash verifies.")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            .padding(BitOSTheme.Spacing.md)
        }
        .navigationTitle("Recovery queue")
        .navigationBarTitleDisplayMode(.inline)
        .background(BitOSTheme.background)
        .confirmationDialog(
            "Discard job?",
            isPresented: Binding(
                get: { discardTarget != nil },
                set: { if !$0 { discardTarget = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Discard (media stays on Blossom until GC)", role: .destructive) {
                if let job = discardTarget {
                    store.jobStore.discard(job.id)
                }
                discardTarget = nil
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func jobCard(_ job: MemePublishJob) -> some View {
        let fraction = CGFloat(min(8, max(0, job.stage))) / 8
        return VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            HStack(spacing: BitOSTheme.Spacing.xs) {
                Text("job \(job.id)")
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Capsule().fill(BitOSTheme.accent.opacity(0.15)))
                    .foregroundStyle(BitOSTheme.accent)
                Text("\(job.mode.capitalized) · \(job.caption.isEmpty ? "untitled" : String(job.caption.prefix(40)))")
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Spacer()
                if job.status == "failed" {
                    Text("stalled").font(.caption2.weight(.bold)).foregroundStyle(BitOSTheme.error)
                }
            }
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(BitOSTheme.surfaceOverlay)
                    Capsule().fill(BitOSTheme.accent).frame(width: proxy.size.width * fraction)
                }
            }
            .frame(height: 5)
            Text("stuck at \(Self.stageNames[min(7, max(0, job.stage))]) · retry re-runs from the stored media · idempotent by content hash")
                .font(.system(size: 10, design: .monospaced))
                .foregroundStyle(BitOSTheme.textSecondary)
            if let error = job.lastError {
                Text(error).font(.caption2).foregroundStyle(BitOSTheme.error)
            }
            if let verified = verifyResults[job.id] {
                Text(verified).font(.caption2).foregroundStyle(BitOSTheme.textSecondary)
            }
            HStack(spacing: BitOSTheme.Spacing.sm) {
                Button {
                    onRetry(job.id)
                } label: {
                    Text("Retry now").font(.caption.weight(.semibold))
                }
                .buttonStyle(.borderedProminent)
                .tint(BitOSTheme.accent)
                .disabled(!job.retryAllowed)
                Button {
                    let ok = store.jobStore.integrityOK(job)
                    verifyResults[job.id] = ok == nil
                        ? "Media file missing — retry will fail fast; discard the job."
                        : (ok! ? "Hash matches the original render ✓ — safe to resume." : "Hash MISMATCH — the stored media changed; discard the job.")
                } label: {
                    Label("Verify integrity", systemImage: "checkmark.shield")
                        .font(.caption)
                }
                .buttonStyle(.bordered)
                Spacer()
                Button(role: .destructive) {
                    discardTarget = job
                } label: {
                    Label("Discard", systemImage: "trash")
                        .font(.caption)
                }
                .buttonStyle(.bordered)
                .tint(BitOSTheme.error)
            }
            if !job.retryAllowed {
                Text("An event was already signed — it may be live; verify on your profile before re-sending.")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.warning)
            }
        }
        .padding(BitOSTheme.Spacing.md)
        .background(BitOSTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))
    }
}

/// Row state shared by the machine rows (named for file scope).
enum MemePublishingViewRowState { case done, current, pending, failed }

// MARK: - Shared bits

/// Post preview thumbnail per mode: graded still (image), first frame
/// (GIF), cover URL or poster tile (video) + duration badge.
struct MemePostPreviewThumb: View {
    let store: MemeEditorStore

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            if store.isVideoMode {
                if let url = store.coverThumbUrl, let target = URL(string: url) {
                    AsyncImage(url: target) { phase in
                        if let image = phase.image {
                            image.resizable().scaledToFill()
                        } else {
                            posterFallback
                        }
                    }
                } else {
                    posterFallback
                }
            } else if store.isGifMode, let frame = store.gifFrames.first {
                Image(uiImage: store.gradedImage(frame.image, cacheKey: "poster-\(frame.id)"))
                    .resizable()
                    .scaledToFill()
            } else if let asset = store.activeAsset {
                Image(uiImage: store.gradedImage(asset.image, cacheKey: "poster-\(asset.id)"))
                    .resizable()
                    .scaledToFill()
            } else {
                Color.black.opacity(0.2)
            }
            if store.isVideoMode, !store.clips.isEmpty {
                Text("\(Int((store.timelineDurationMs + 999) / 1000))s")
                    .font(.system(size: 8, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 4)
                    .padding(.vertical, 2)
                    .background(Color.black.opacity(0.6))
                    .clipShape(RoundedRectangle(cornerRadius: 3))
                    .padding(3)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(BitOSTheme.border))
    }

    private var posterFallback: some View {
        ZStack {
            Color.black.opacity(0.35)
            Image(systemName: "play.fill")
                .font(.system(size: 18))
                .foregroundStyle(.white.opacity(0.9))
        }
    }
}

private struct FlowTagRow: View {
    let tags: [String]
    let onRemove: (String) -> Void

    var body: some View {
        LazyVGrid(
            columns: [GridItem(.adaptive(minimum: 88), spacing: BitOSTheme.Spacing.xs)],
            alignment: .leading,
            spacing: BitOSTheme.Spacing.xs
        ) {
            ForEach(tags, id: \.self) { tag in
                HStack(spacing: 4) {
                    Text("#\(tag)")
                        .font(.caption2.weight(.semibold))
                        .lineLimit(1)
                    Button {
                        onRemove(tag)
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 7, weight: .bold))
                    }
                    .accessibilityLabel("Remove \(tag)")
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Capsule().fill(BitOSTheme.accent.opacity(0.15)))
                .foregroundStyle(BitOSTheme.accent)
            }
        }
    }
}

/// One settings row (prototype list-row): plate icon, title + subtitle and
/// an optional trailing value or chevron.
private struct DetailRow: View {
    enum Trailing { case value(String) }
    let icon: String
    var tint: Color = BitOSTheme.textSecondary
    let title: String
    let subtitle: String
    var trailing: Trailing?

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.md) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 28, height: 28)
                .background(Circle().fill(BitOSTheme.background))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            Spacer(minLength: 0)
            if case .value(let value) = trailing {
                Text(value)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(BitOSTheme.accent)
            }
        }
        .contentShape(Rectangle())
    }
}

/// One preflight checklist line: green check / amber alert + mono meta.
private struct PreflightLine: View {
    let done: Bool
    let label: String
    let meta: String

    var body: some View {
        HStack(alignment: .top, spacing: BitOSTheme.Spacing.sm) {
            Image(systemName: done ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                .font(.system(size: 14))
                .foregroundStyle(done ? BitOSTheme.success : BitOSTheme.warning)
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.subheadline.weight(.semibold))
                Text(meta)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 8)
    }
}
