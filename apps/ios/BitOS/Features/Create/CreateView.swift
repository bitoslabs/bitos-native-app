import SwiftUI
import PhotosUI
import CryptoKit
import UniformTypeIdentifiers
import BusinessCore

/// Create surface: fast paths into capture and the Studio. Camera, import
/// and editors are native-only features (CAP/EDT epics); the chooser is the
/// stable entry contract.
struct CreateView: View {
    @State private var showCamera = false
    /// Studio import-media → editor (CAP/EDT): one picker, one seeding
    /// path — the editor's Post details pipeline is the ONLY publish
    /// route from this hub (the standalone "New video" sheet is gone;
    /// ux-ui-flows §6 parity with Android's Import media action).
    @State private var showImportPicker = false
    @State private var importItem: PhotosPickerItem?
    @State private var importing = false
    @State private var importError: String?
    @State private var showMeme = false
    @State private var templateSeed: String?
    @State private var sharedSeed: (tagsJson: String, content: String)?
    @State private var sharedTemplateStore: SharedTemplateStore?
    /// Shared sounds (MST-047 W2): "Use sound" rides the MST-050 Wave C/D
    /// audio-only seed path (hash-verified download, no re-upload).
    @State private var soundSeed: MemeSoundSeed?
    @State private var sharedSoundStore: SharedSoundStore?
    @State private var resumeSlotId: String?
    /// CAP→MEM handoff: latest camera take seeding the editor (video/mp4).
    @State private var memeSeed: Data?
    /// M5 take-native handoff: ALL camera takes as timeline clips.
    @State private var memeSeeds: [Data]?
    @State private var slotsRevision = 0
    /// Mass production (MST-048) flows inside this surface, like the editor.
    @State private var massFlow: MassBatchFlow?
    /// Hub batch-queue bar (scr-home): recomputed when a session closes.
    @State private var batchSummary: MassBatchSummary?
    private let slotStore = MemeProjectStore()
    @Environment(AppEnvironment.self) private var environment

    private var slots: [MemeProjectStore.SlotEntryUi] {
        slotsRevision // recompute on revision bump (delete/editor close)
        return slotStore.listSlots()
    }

    private func slotPoster(_ slot: MemeProjectStore.SlotEntryUi) -> UIImage? {
        slotStore.posterURL(slot.slotId).flatMap { UIImage(contentsOfFile: $0.path) }
    }

    private struct QuickAction: Identifiable {
        let id = UUID()
        let symbol: String
        let title: LocalizedStringKey
        let description: LocalizedStringKey
        /// False rows render dimmed with a "Soon" chip — never a dead tap.
        var enabled = false
    }

    private let quickActions: [QuickAction] = [
        QuickAction(symbol: AppIcons.camera, title: "Record Bitz", description: "Record a portrait clip with segment control", enabled: true),
        QuickAction(symbol: AppIcons.photo, title: "Import media", description: "Pick a video and polish it in the studio editor", enabled: true),
        // M1 wave 1: the image editing core is live (export/publish next).
        QuickAction(symbol: AppIcons.emoji, title: "Quick MEM", description: "Caption, look and stickers in seconds", enabled: true),
        QuickAction(symbol: AppIcons.musicNote, title: "Use a sound", description: "Start a project from a licensed sound"),
        QuickAction(symbol: AppIcons.sparkles, title: "Remix", description: "Build on a template with attribution"),
    ]

    var body: some View {
        NavigationStack {
            if let massFlow {
                MassBatchAreaView(flow: massFlow, onExit: {
                    self.massFlow = nil
                    batchSummary = MassBatchFlow.summaryOfNewestBatch()
                })
            } else {
                hubScreen
            }
        }
        .onAppear {
            if batchSummary == nil {
                batchSummary = MassBatchFlow.summaryOfNewestBatch()
            }
            if sharedTemplateStore == nil {
                let store = SharedTemplateStore(pool: environment.relayPool)
                store.start()
                sharedTemplateStore = store
            }
            if sharedSoundStore == nil {
                let store = SharedSoundStore(pool: environment.relayPool)
                store.start()
                sharedSoundStore = store
            }
        }
    }
    /// The hub list + covers, split out of `body` — the combined
    /// expression exceeded the type-checker's budget.
    private var hubScreen: some View {
        List {
            batchQueueSection
            startTilesSection
            templatesSection
            sharedTemplatesSection
            sharedSoundsSection
            continueCreatingSection
            quickActionsSection
            projectLibrarySection
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(BitOSTheme.background)
        .navigationTitle("Create")
        .fullScreenCover(isPresented: $showCamera) {
            CameraScreen(
                onCaptured: { data, _ in
                    // Record → editor: the used take seeds video mode as
                    // its own timeline clip; caption/publish flow through
                    // the editor's Post details pipeline (the ONE studio
                    // publish path).
                    showCamera = false
                    memeSeeds = [data]
                    showMeme = true
                },
                onImport: {
                    // Library path from the record screen: same picker →
                    // same editor pipeline as the hub's Import media row.
                    // Presenting right as the camera cover dismisses
                    // would race SwiftUI's single-presentation rule, so
                    // the picker opens a beat after the cover is gone.
                    showCamera = false
                    Task {
                        try? await Task.sleep(nanoseconds: 350_000_000)
                        showImportPicker = true
                    }
                },
                onOpenMeme: { payload in
                    showCamera = false
                    memeSeed = payload?.0
                    showMeme = true
                },
                onOpenMemeList: { takes in
                    // M5: every take seeds video mode as its own
                    // timeline clip — no merge re-encode.
                    showCamera = false
                    memeSeeds = takes.map(\.0)
                    showMeme = true
                },
                onCancel: { showCamera = false }
            )
        }
        .fullScreenCover(isPresented: $showMeme, onDismiss: { memeSeed = nil; memeSeeds = nil; soundSeed = nil }) {
            MemeEditorView(
                slotStore: slotStore,
                resumeSlot: resumeSlotId.flatMap { slotStore.loadSlot($0) },
                templateId: templateSeed,
                sharedTagsJson: sharedSeed?.tagsJson,
                sharedContent: sharedSeed?.content,
                videoSeed: memeSeed,
                videoSeeds: memeSeeds,
                soundSeed: soundSeed,
                onSlotsChanged: {
                    slotsRevision += 1
                    templateSeed = nil
                    sharedSeed = nil
                    soundSeed = nil
                },
                onMakeVariations: { projectJson, posterPng in
                    showMeme = false
                    let flow = MassBatchFlow()
                    massFlow = flow
                    flow.createFromDesign(projectJson: projectJson, posterPng: posterPng)
                }
            )
        }
        // Studio import-media → editor: a picked library video seeds
        // video mode as its own timeline clip (CAP→MEM parity).
        .photosPicker(isPresented: $showImportPicker, selection: $importItem, matching: .videos)
        .onChange(of: importItem) { _, item in
            guard let item else { return }
            importItem = nil
            importing = true
            Task {
                let data = try? await item.loadTransferable(type: Data.self)
                importing = false
                if let rejection = StudioImportRules.rejection(data?.count) {
                    importError = rejection
                } else if let data {
                    memeSeeds = [data]
                    showMeme = true
                }
            }
        }
        // Named progress stage — the hub never looks dead while a large
        // library video streams into memory.
        .overlay {
            if importing {
                ZStack {
                    Rectangle().fill(.black.opacity(0.35)).ignoresSafeArea()
                    VStack(spacing: BitOSTheme.Spacing.sm) {
                        ProgressView().controlSize(.large)
                        Text("Preparing your video…")
                            .font(.subheadline)
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                    .padding(BitOSTheme.Spacing.xl)
                    .background(BitOSTheme.surface, in: RoundedRectangle(cornerRadius: BitOSTheme.Radius.lg))
                }
            }
        }
        // Named, non-destructive rejections (ux-ui-flows §3): the hub
        // stays untouched; the user learns exactly what to do next.
        .alert(
            "Couldn't open that video",
            isPresented: Binding(
                get: { importError != nil },
                set: { if !$0 { importError = nil } }
            )
        ) {
            Button("OK") { importError = nil }
        } message: {
            Text(importError ?? "")
        }
    }


    @ViewBuilder private var batchQueueSection: some View {
                if let summary = batchSummary {
                    Section {
                        Button {
                            massFlow = MassBatchFlow()
                        } label: {
                            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
                                HStack(spacing: BitOSTheme.Spacing.sm) {
                                    HexIcon(
                                        systemName: AppIcons.settings, size: 34,
                                        background: BitOSTheme.accent.opacity(0.16)
                                    )
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(summary.name).font(.subheadline.weight(.semibold))
                                        Text(
                                            "\(summary.rows) variants · \(summary.published) published · \(summary.awaiting) awaiting approval"
                                        )
                                        .font(.caption)
                                        .foregroundStyle(BitOSTheme.textSecondary)
                                    }
                                    Spacer()
                                    Text("Open")
                                        .font(.caption.weight(.bold))
                                        .foregroundStyle(BitOSTheme.accent)
                                }
                                GeometryReader { proxy in
                                    ZStack(alignment: .leading) {
                                        Capsule().fill(BitOSTheme.surfaceOverlay)
                                        Capsule()
                                            .fill(BitOSTheme.accent)
                                            .frame(width: proxy.size.width * summary.fraction)
                                    }
                                }
                                .frame(height: 6)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
    }

    private var startTilesSection: some View {
                Section {
                    HStack(spacing: BitOSTheme.Spacing.sm) {
                        startTile(AppIcons.video, "Bitz", "camera") { showCamera = true }
                        startTile(AppIcons.photo, "Meme", "quick edit") { showMeme = true }
                        startTile(AppIcons.settings, "Batch", "mass produce") { massFlow = MassBatchFlow() }
                    }
                    .listRowInsets(EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12))
                    .listRowBackground(Color.clear)
                }
                // Templates rail (MST-040): the seeded built-in pack.
    }

    @ViewBuilder private var templatesSection: some View {
                Section("Templates") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: BitOSTheme.Spacing.sm) {
                            ForEach(templateRows, id: \.id) { template in
                                templateCard(template)
                            }
                        }
                    }
                    .listRowInsets(EdgeInsets(top: 4, leading: 12, bottom: 4, trailing: 12))
                    .listRowBackground(Color.clear)
                }
    }

    /// One rail card (split out — the inline ForEach body exceeded the
    /// type-checker's budget).
    private func templateCard(_ template: TemplateRow) -> some View {
        Button {
            templateSeed = template.id
            showMeme = true
        } label: {
            TemplateCoverCard(
                emoji: template.emoji, label: template.label,
                gradient: StudioCoverGradient.gradient(at: templateRows.firstIndex(where: { $0.id == template.id }) ?? 0)
            )
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder private var sharedTemplatesSection: some View {
                // Shared templates (MST-045): relay-fetched kind-30078 pack.
                if let sharedRows = sharedTemplateStore?.rows, !sharedRows.isEmpty {
                    Section("Shared templates") {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: BitOSTheme.Spacing.sm) {
                                ForEach(sharedRows) { row in
                                    sharedTemplateCard(row, in: sharedRows)
                                }
                            }
                        }
                        .listRowInsets(EdgeInsets(top: 4, leading: 12, bottom: 4, trailing: 12))
                        .listRowBackground(Color.clear)
                    }
                }
    }

    /// One shared-rail card (split out with templateCard — same reason).
    private func sharedTemplateCard(_ row: SharedTemplateStore.Row, in rows: [SharedTemplateStore.Row]) -> some View {
        Button {
            sharedSeed = (row.tagsJson, row.content)
            showMeme = true
        } label: {
            TemplateCoverCard(
                emoji: row.emoji, label: row.label,
                gradient: StudioCoverGradient.gradient(at: rows.firstIndex(where: { $0.id == row.id }) ?? 0),
                priceLabel: row.priceLabel
            )
        }
        .buttonStyle(.plain)
    }

    /// Shared sounds (MST-047 W2): relay-fetched kind-30078 licensed
    /// library — license chip + duration; tap = "Use sound" through the
    /// Wave C/D audio-only seed path.
    @ViewBuilder private var sharedSoundsSection: some View {
        if let sharedRows = sharedSoundStore?.rows, !sharedRows.isEmpty {
            Section("Shared sounds") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: BitOSTheme.Spacing.sm) {
                        ForEach(sharedRows) { row in
                            sharedSoundCard(row, in: sharedRows)
                        }
                    }
                }
                .listRowInsets(EdgeInsets(top: 4, leading: 12, bottom: 4, trailing: 12))
                .listRowBackground(Color.clear)
            }
        }
    }

    private func sharedSoundCard(_ row: SharedSoundStore.Row, in rows: [SharedSoundStore.Row]) -> some View {
        Button {
            soundSeed = MemeSoundSeed(
                eventId: row.eventId,
                authorPubkey: row.authorPubkey,
                label: row.label,
                mediaUrl: row.url,
                isAudioOnly: true,
                sha256: row.sha256
            )
            showMeme = true
        } label: {
            TemplateCoverCard(
                emoji: "♪", label: row.label,
                gradient: StudioCoverGradient.gradient(at: rows.firstIndex(where: { $0.id == row.id }) ?? 0),
                priceLabel: row.durationMs > 0 ? "\(row.durationMs / 1000)s · \(row.license)" : row.license
            )
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder private var continueCreatingSection: some View {
                // Continue creating (MST-018, EDT-001/002): ≤6 LRU slots.
                if !slots.isEmpty {
                    Section("Continue creating") {
                        ForEach(slots) { slot in
                            SlotRow(slot: slot, poster: slotPoster(slot)) {
                                resumeSlotId = slot.slotId
                                showMeme = true
                            } onDelete: {
                                slotStore.deleteSlot(slot.slotId)
                                slotsRevision += 1
                            }
                        }
                    }
                }
    }

    private var quickActionsSection: some View {
                Section {
                    ForEach(Array(quickActions.enumerated()), id: \.element.id) { index, action in
                        HStack(spacing: BitOSTheme.Spacing.md) {
                            AppIcons.image(for: action.symbol)
                                .font(.system(size: 18, weight: .medium))
                                .foregroundStyle(BitOSTheme.accent)
                                .frame(width: 42, height: 42)
                                .background(BitOSTheme.accentContainer)
                                .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(action.title).font(.subheadline.weight(.semibold))
                                Text(action.description)
                                    .font(.caption)
                                    .foregroundStyle(BitOSTheme.textSecondary)
                            }
                            Spacer()
                            if !action.enabled {
                                Text("Soon")
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundStyle(BitOSTheme.textSecondary)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 3)
                                    .background(Capsule().fill(BitOSTheme.surfaceOverlay))
                            }
                        }
                        .opacity(action.enabled ? 1 : 0.55)
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            guard action.enabled else { return }
                            if index == 0 { showCamera = true }
                            if index == 1 { showImportPicker = true }
                            if index == 2 { showMeme = true }
                        }
                    }
                }
    }

    private var projectLibrarySection: some View {
                Section {
                    VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
                        Text("Project library").font(.subheadline.weight(.semibold))
                        Text("Drafts live on-device by default. The library lists projects, storage usage and recovery state once the editor phase lands.")
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                }
    }


    private struct TemplateRow: Identifiable {
        let id: String, label: String, emoji: String
    }

    private var templateRows: [TemplateRow] {
        guard let data = FrameworkBusinessCoreClient().memeTemplates().data(using: .utf8),
              let rows = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return rows.compactMap { row in
            guard let id = row["id"] as? String else { return nil }
            return TemplateRow(id: id, label: (row["label"] as? String) ?? id,
                               emoji: (row["emoji"] as? String) ?? "🖼")
        }
    }

    /// One "Start something new" tile (scr-home 3-up grid).
    private func startTile(
        _ symbol: String, _ title: LocalizedStringKey, _ subtitle: LocalizedStringKey,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: BitOSTheme.Spacing.xs) {
                AppIcons.image(for: symbol)
                    .font(.system(size: 26, weight: .medium))
                    .foregroundStyle(BitOSTheme.accent)
                Text(title).font(.subheadline.weight(.bold))
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, BitOSTheme.Spacing.sm)
            .background(BitOSTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md))
        }
        .buttonStyle(.plain)
    }
}

/// Studio import-media gate (Android `ImportedVideoRules` parity): a
/// picked library video seeds the meme editor — the ONE publish path
/// from this hub. Rejections name the reason AND the fix; the hub state
/// is never touched by a rejected pick. The byte cap is the SHARED
/// cross-platform constant (`MemeVideoCutRules.MAX_SOURCE_BYTES`, common-
/// tested) — never a local literal, so the two apps cannot drift.
private enum StudioImportRules {
    static let maxBytes = Int(MemeVideoCutRules.shared.MAX_SOURCE_BYTES)

    /// nil = seed the editor; else the named rejection for the alert.
    static func rejection(_ byteCount: Int?) -> String? {
        guard let byteCount, byteCount > 0 else {
            return "This video could not be read. Try another file."
        }
        guard byteCount <= maxBytes else {
            return "This video is larger than \(maxBytes / (1024 * 1024)) MB. Trim it first, then try again."
        }
        return nil
    }
}

/// One "Continue creating" row: poster thumb, label, relative time,
/// one-tap Resume into the exact persisted state, explicit delete.
private struct SlotRow: View {
    let slot: MemeProjectStore.SlotEntryUi
    let poster: UIImage?
    let onResume: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.md) {
            Group {
                if let poster {
                    Image(uiImage: poster)
                        .resizable().scaledToFill()
                } else {
                    AppIcons.image(for: AppIcons.photo)
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
            }
            .frame(width: 40, height: 52)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .background(RoundedRectangle(cornerRadius: 8).fill(BitOSTheme.surface))

            VStack(alignment: .leading, spacing: 2) {
                Text(slot.label)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Text("\(SlotRow.relativeTime(slot.updatedAtMs)) · quick editor")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            Spacer()
            Button("Resume", action: onResume)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(BitOSTheme.accent)
            Button {
                onDelete()
            } label: {
                AppIcons.image(for: AppIcons.close)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            .accessibilityLabel("Delete draft")
        }
    }

    /// Mirrors the shared MemeSlotRules.relativeTime semantics.
    static func relativeTime(_ updatedAtMs: Int64) -> String {
        let delta = max(0, Date.now.timeIntervalSince1970 * 1000 - Double(updatedAtMs)) / 1000
        let minutes = Int(delta / 60)
        switch minutes {
        case ..<1: return "just now"
        case ..<60: return "\(minutes) min ago"
        case ..<(60 * 24): return "\(minutes / 60) h ago"
        case ..<(60 * 48): return "yesterday"
        default: return "\(minutes / (60 * 24)) d ago"
        }
    }
}

// ── Honeycomb studio chrome (docs/ui/shared/honeycomb.css parity) ──────

/// The scr-home template cover gradients (honeycomb.css `grad-*`),
/// picked stably by rail position — same ladder on Android.
enum StudioCoverGradient {
    private static let covers: [[Color]] = [
        [Color(hex: 0x0B1E3A), Color(hex: 0x2858C8), Color(hex: 0x06102A)],  // lightning
        [Color(hex: 0x3A1505), Color(hex: 0xC2570F), Color(hex: 0x200B02)],  // mine
        [Color(hex: 0x2B1802), Color(hex: 0x7A4A05), Color(hex: 0x1A0F01)],  // node
        [Color(hex: 0x1E1033), Color(hex: 0x6D28D9), Color(hex: 0x150B26)]   // story
    ]

    static func gradient(at index: Int) -> LinearGradient {
        let colors = covers[((index % covers.count) + covers.count) % covers.count]
        // Diagonal wash (top-leading → bottom-trailing), matching the Android
        // rail and the hex-avatar gradient axis.
        return LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

/// scr-home template card: gradient cover with the pack emoji, label
/// row, and the zap-priced sats chip on marketplace templates.
struct TemplateCoverCard: View {
    let emoji: String
    let label: String
    let gradient: LinearGradient
    var priceLabel: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack {
                // Hex-clipped gradient plate (HexIcon/settings-tile geometry).
                gradient
                    .clipShape(HexShape())
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                Text(emoji).font(.title2)
            }
            .frame(width: 88, height: 56)
            if let priceLabel {
                HStack(spacing: 2) {
                    AppIcons.image(for: AppIcons.zap)
                        .font(.system(size: 9, weight: .bold))
                    Text(priceLabel)
                        .font(.system(size: 9, weight: .bold))
                        .lineLimit(1)
                }
                .foregroundStyle(Color(hex: 0xFFB000))
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(Capsule().fill(Color.black.opacity(0.35)))
                .padding(.top, 4)
            }
            Text(label)
                .font(.caption.weight(.semibold))
                .lineLimit(1)
                .padding(.top, 4)
                .padding(.bottom, BitOSTheme.Spacing.xs)
                .padding(.horizontal, BitOSTheme.Spacing.xs)
        }
        .frame(width: 88, alignment: .leading)
        .background(BitOSTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))
    }
}

// ══════════════════════════════════════════════════════════════════════
// Mass production (APP-019 M4 wave 5 / MST-048) — integrated into the
// Create hub per docs/ui/app-15 scr-batch/scr-review/scr-pub. All rules
// (validation, resolution, approval hashing, queue, CSV, fork-on-edit)
// run in shared business-core through the four bridge seams; Swift owns
// files, raster previews and the per-event publish machine.
// ══════════════════════════════════════════════════════════════════════

/// On-disk batch store: Application Support/studio/mass/<id>/ with
/// batch.json, asset-master.png, assets/ row images and preview posters.
/// Every read is lenient — a corrupt store degrades to empty.
private struct MassBatchFiles {
    let root: URL

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        root = base.appendingPathComponent("studio/mass", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    func batchDir(_ id: String) -> URL {
        root.appendingPathComponent(id, isDirectory: true)
    }

    private var indexURL: URL { root.appendingPathComponent("index.json") }

    /// New batch from a master image; nil when the bytes are unreadable.
    func create(masterPng: Data, client: any BusinessCoreClient) -> String? {
        guard UIImage(data: masterPng) != nil else { return nil }
        let docJson = client.massBatchNew("New batch", nowMs: Int64(Date.now.timeIntervalSince1970 * 1000))
        guard !docJson.isEmpty, let id = Self.batchId(of: docJson) else { return nil }
        let dir = batchDir(id)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? masterPng.write(to: dir.appendingPathComponent("asset-master.png"))
        save(docJson)
        return load(id)
    }

    /// MUX-06: a batch FROM an editor design — the frozen placeholder
    /// project rides the recipe; the rendered poster is the master.
    func createFromDesign(projectJson: String, posterPng: Data, client: any BusinessCoreClient) -> String? {
        guard UIImage(data: posterPng) != nil else { return nil }
        let nowMs = Int64(Date.now.timeIntervalSince1970 * 1000)
        let batchId = "mb-" + String(nowMs, radix: 36)
        let docJson = client.massBatchFromDesign(projectJson, batchId: batchId, name: "Variations", nowMs: nowMs)
        guard !docJson.isEmpty, !docJson.contains("\"error\""),
              let id = Self.batchId(of: docJson) else { return nil }
        let dir = batchDir(id)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? posterPng.write(to: dir.appendingPathComponent("asset-master.png"))
        save(docJson)
        return load(id)
    }

    func save(_ docJson: String) {
        guard let id = Self.batchId(of: docJson),
              let data = docJson.data(using: .utf8) else { return }
        let dir = batchDir(id)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? data.write(to: dir.appendingPathComponent("batch.json"))
        var entries = listBatches().filter { $0.id != id }
        let name = Self.field("name", of: docJson) ?? "Untitled batch"
        entries.insert((id, name, Date.now.timeIntervalSince1970 * 1000), at: 0)
        writeIndex(Array(entries.prefix(6)))
    }

    /// Load + crash recovery: orphaned publishing states reset to waiting
    /// so the queue re-runs deterministically on relaunch.
    func load(_ id: String) -> String? {
        let url = batchDir(id).appendingPathComponent("batch.json")
        guard var json = try? String(contentsOf: url, encoding: .utf8),
              let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        if let states = root["states"] as? [String: [String: Any]] {
            var next = root
            var recovered = false
            var cleaned: [String: Any] = [:]
            for (rowId, raw) in states {
                var row = raw
                if (row["publish"] as? String)?.lowercased() == "publishing" {
                    row["publish"] = "waiting"
                    row.removeValue(forKey: "failure")
                    recovered = true
                }
                cleaned[rowId] = row
            }
            if recovered {
                next["states"] = cleaned
                if let redata = try? JSONSerialization.data(withJSONObject: next),
                   let rejson = String(data: redata, encoding: .utf8) {
                    json = rejson
                }
            }
        }
        return json
    }

    func listBatches() -> [(id: String, name: String, updatedAt: Double)] {
        guard let data = try? Data(contentsOf: indexURL),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = root["batches"] as? [[String: Any]] else { return [] }
        return rows.compactMap { row in
            guard let id = row["id"] as? String, !id.isEmpty else { return nil }
            return (id, (row["name"] as? String) ?? "Untitled batch",
                    (row["updatedAt"] as? NSNumber)?.doubleValue ?? 0)
        }
    }

    private func writeIndex(_ entries: [(id: String, name: String, updatedAt: Double)]) {
        let rows: [[String: Any]] = entries.map {
            ["id": $0.id, "name": $0.name, "updatedAt": $0.updatedAt]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: ["v": 1, "batches": rows]) else { return }
        try? data.write(to: indexURL)
    }

    func delete(_ id: String) {
        try? FileManager.default.removeItem(at: batchDir(id))
        writeIndex(listBatches().filter { $0.id != id })
    }

    func masterURL(_ id: String) -> URL? {
        let url = batchDir(id).appendingPathComponent("asset-master.png")
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    func assetURL(_ id: String, file: String) -> URL? {
        guard !file.isEmpty, !file.hasPrefix("/"), !file.contains("..") else { return nil }
        let url = batchDir(id).appendingPathComponent("assets").appendingPathComponent(file)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// Idempotent row-image copy-in (CAP-005: picker items are transient).
    func writeAsset(_ id: String, rowId: String, slotId: String, data: Data) -> String? {
        guard !data.isEmpty, data.count <= 8 * 1024 * 1024, UIImage(data: data) != nil else { return nil }
        let name = "\(rowId)-\(slotId).img"
        let dir = batchDir(id).appendingPathComponent("assets")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        do { try data.write(to: dir.appendingPathComponent(name)); return name } catch { return nil }
    }

    static func posterName(_ rowId: String) -> String { "poster-\(rowId).jpg" }

    func writePoster(_ id: String, rowId: String, jpeg: Data) {
        try? jpeg.write(to: batchDir(id).appendingPathComponent(Self.posterName(rowId)))
    }

    func posterURL(_ id: String, rowId: String) -> URL? {
        let url = batchDir(id).appendingPathComponent(Self.posterName(rowId))
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    static func batchId(of docJson: String) -> String? { field("id", of: docJson) }

    static func field(_ key: String, of docJson: String) -> String? {
        guard let data = docJson.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return root[key] as? String
    }
}

/// Hub batch-queue bar data (scr-home mockup): newest batch progress.
private struct MassBatchSummary {
    let name: String
    let rows: Int
    let published: Int
    let awaiting: Int
    var fraction: CGFloat { rows > 0 ? CGFloat(published) / CGFloat(rows) : 0 }
}

private func massSummary(of docJson: String) -> MassBatchSummary? {
    guard let data = docJson.data(using: .utf8),
          let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let rows = root["rows"] as? [[String: Any]] else { return nil }
    var published = 0
    for raw in ((root["states"] as? [String: [String: Any]]) ?? [:]).values {
        if (raw["publish"] as? String)?.lowercased() == "published" { published += 1 }
    }
    let name = MassBatchFiles.field("name", of: docJson) ?? "Untitled batch"
    return MassBatchSummary(
        name: name, rows: rows.count, published: published,
        awaiting: max(0, rows.count - published)
    )
}

/// Screen-owned batch state: every mutation persists immediately through
/// [MassBatchFiles] (kill/relaunch loses nothing, EDT-002); previews and
/// the publish queue run sequentially and persist around each per-event
/// step (crash-safe advance — duplicate retries never duplicate signs).
@Observable
private final class MassBatchFlow {
    enum Phase { case pick, setup, review, publish }

    struct SlotUi: Identifiable, Equatable {
        let id: String, name: String, type: String
        let required: Bool, enumValues: [String]
        var isAsset: Bool { type.contains("asset") }
    }

    struct RowUi: Identifiable, Equatable {
        let id: String
        let index: Int
        let values: [String: String]
        let assets: [String: String]
        let severity: String   // ok | warn | blocker
        let notes: [String]
        let approved: Bool
        let publish: String    // waiting | publishing | published | failed
        let name: String, caption: String
        let projectJson: String
        let assetMap: [String: String]
        var blocked: Bool { severity == "blocker" }
        var queueable: Bool { severity != "blocker" }
    }

    struct Plan {
        let name: String
        let recipeVersion: Int
        let frozen: Bool
        let naming: String, caption: String, cw: String
        let slots: [SlotUi]
        let rows: [RowUi]
        let ok: Int, warn: Int, blocked: Int
        let queueCount: Int
        var queueableCount: Int { ok + warn }
        /** Queue = approved, not settled, stable row order (shared rule). */
        var queue: [RowUi] {
            rows.filter { $0.approved && $0.queueable && ($0.publish == "waiting" || $0.publish == "failed") }
        }
    }

    let files = MassBatchFiles()
    let client = FrameworkBusinessCoreClient()
    private(set) var docJson: String?
    private(set) var plan: Plan?
    var phase: Phase = .pick
    private(set) var generating = false
    private(set) var progress = (0, 0)
    private(set) var publishing = false
    var message: String?

    // ── Lifecycle ───────────────────────────────────────────────────

    func create(masterData: Data) {
        if let doc = files.create(masterPng: masterData, client: client) {
            open(doc)
        } else {
            message = "That image could not be read — pick a PNG or JPEG."
        }
    }

    /// MUX-06 "Make variations": open a batch seeded from this design.
    func createFromDesign(projectJson: String, posterPng: Data) {
        if let doc = files.createFromDesign(projectJson: projectJson, posterPng: posterPng, client: client) {
            open(doc)
        } else {
            message = "Image designs with at least one caption — GIF and video stay on the renderer roadmap."
        }
    }

    func open(_ docJson: String) {
        self.docJson = docJson
        phase = .setup
        refreshPlan()
    }

    func close() {
        docJson = nil
        plan = nil
        phase = .pick
    }

    // ── Ops (all through the shared rules via the bridge) ──────────

    private func op(_ opJson: String) {
        guard let doc = docJson else { return }
        let next = client.massBatchOp(doc, opJson: opJson)
        if next.isEmpty || next == doc { return }
        docJson = next
        files.save(next)
        refreshPlan()
    }

    func addRow() { op(#"{"op":"addRow"}"#) }

    func removeRow(_ rowId: String) { op(#"{"op":"removeRow","row":"\#(rowId)"}"#) }

    func setValue(rowId: String, slotId: String, value: String) {
        let v = value.replacingOccurrences(of: "\"", with: "'")
        op(#"{"op":"setValue","row":"\#(rowId)","slot":"\#(slotId)","value":"\#(v)"}"#)
    }

    func setAsset(rowId: String, slotId: String, data: Data?) {
        guard let doc = docJson, let id = MassBatchFiles.batchId(of: doc) else { return }
        if let data, let file = files.writeAsset(id, rowId: rowId, slotId: slotId, data: data) {
            op(#"{"op":"setAsset","row":"\#(rowId)","slot":"\#(slotId)","file":"\#(file)"}"#)
        } else {
            op(#"{"op":"setAsset","row":"\#(rowId)","slot":"\#(slotId)"}"#)
        }
    }

    /// Recipe-content edits fork once rows exist (product doc §3).
    func editRecipe(naming: String? = nil, caption: String? = nil) {
        var parts: [String] = []
        if let naming { parts.append("\"naming\":\"\(naming.replacingOccurrences(of: "\"", with: "'"))\"") }
        if let caption { parts.append("\"caption\":\"\(caption.replacingOccurrences(of: "\"", with: "'"))\"") }
        guard !parts.isEmpty else { return }
        op(#"{"op":"editRecipe",\#(parts.joined(separator: ","))}"#)
    }

    func importCsv(_ text: String) {
        guard let doc = docJson else { return }
        let result = client.massBatchImportCsv(doc, csv: text)
        guard let data = result.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let next = root["doc"] as? String else { return }
        message = ((root["notes"] as? [String]) ?? []).joined(separator: "\n")
        if next != doc {
            docJson = next
            files.save(next)
            refreshPlan()
        }
    }

    func approve(_ rowId: String, _ approve: Bool) {
        op(#"{"op":"approve","row":"\#(rowId)","approve":\#(approve ? "true" : "false")","nowMs":\#(Int64(Date.now.timeIntervalSince1970 * 1000))}"#)
    }

    /// MUX-09 bulk local export: per-row results ("" = saved, else the
    /// error). Successes are never re-exported; Retry re-runs failures only.
    var exportResults: [String: String] = [:]
    var exportingBatch = false

    func exportSelected(_ rowIds: [String]) async {
        guard let plan, generating == false, exportingBatch == false else { return }
        exportingBatch = true
        defer { exportingBatch = false }
        let client = self.client
        for row in plan.rows where rowIds.contains(row.id) && row.queueable {
            if exportResults[row.id] == "" { continue } // saved already — never duplicate
            guard let id = docJson.flatMap({ MassBatchFiles.batchId(of: $0) }),
                  let source = sourceImage(batchId: id, row: row) else {
                exportResults[row.id] = "source unreadable"
                continue
            }
            let projectJson = row.projectJson
            do {
                let png = try await Task.detached(priority: .userInitiated) {
                    try await MemeRaster.renderPngData(asset: source, projectJson: projectJson, client: client)
                }.value
                try await MemeRaster.saveToPhotos(png)
                exportResults[row.id] = ""
            } catch {
                exportResults[row.id] = error.localizedDescription
            }
        }
    }

    var exportFailedIds: [String] {
        exportResults.filter { !$0.value.isEmpty }.map(\.key)
    }

    /// MUX-08: full-preview target row (nil = closed).
    var previewingRowId: String?

    func approveAll() {
        op(#"{"op":"approveAll","nowMs":\#(Int64(Date.now.timeIntervalSince1970 * 1000)),"readable":\#(readableJson())}"#)
    }

    // ── Plan ────────────────────────────────────────────────────────

    func refreshPlan() {
        guard let doc = docJson else { return }
        let raw = client.massBatchPlan(doc, readableJson: readableJson())
        plan = Self.parsePlan(raw)
    }

    private func readableJson() -> String {
        guard let doc = docJson, let id = MassBatchFiles.batchId(of: doc),
              let data = doc.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "[]" }
        let names = ((root["rows"] as? [[String: Any]]) ?? [])
            .flatMap { ($0["assets"] as? [String: String])?.values.map { String($0) } ?? [] }
            .filter { files.assetURL(id, file: $0) != nil }
        let array = names.map { "\"\($0)\"" }.joined(separator: ",")
        return "[\(array)]"
    }

    private static func parsePlan(_ raw: String) -> Plan? {
        guard let data = raw.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        let slots = ((root["slots"] as? [[String: Any]]) ?? []).compactMap { row -> SlotUi? in
            guard let id = row["id"] as? String else { return nil }
            return SlotUi(
                id: id, name: (row["name"] as? String) ?? id,
                type: (row["type"] as? String) ?? "short_text",
                required: (row["required"] as? Bool) ?? false,
                enumValues: (row["enum"] as? [String]) ?? []
            )
        }
        let rows = ((root["rows"] as? [[String: Any]]) ?? []).compactMap { row -> RowUi? in
            guard let id = row["id"] as? String else { return nil }
            return RowUi(
                id: id,
                index: (row["index"] as? Int) ?? 0,
                values: (row["values"] as? [String: String]) ?? [:],
                assets: (row["assets"] as? [String: String]) ?? [:],
                severity: (row["severity"] as? String) ?? "ok",
                notes: (row["notes"] as? [String]) ?? [],
                approved: (row["approved"] as? Bool) ?? false,
                publish: (row["publish"] as? String) ?? "waiting",
                name: (row["name"] as? String) ?? id,
                caption: (row["caption"] as? String) ?? "",
                projectJson: (row["projectJson"] as? String) ?? "",
                assetMap: (row["assetMap"] as? [String: String]) ?? [:]
            )
        }
        let counts = root["counts"] as? [String: Any] ?? [:]
        return Plan(
            name: (root["name"] as? String) ?? "Untitled batch",
            recipeVersion: (root["recipeVersion"] as? Int) ?? 1,
            frozen: (root["frozen"] as? Bool) ?? false,
            naming: (root["naming"] as? String) ?? "memes_{i}",
            caption: (root["caption"] as? String) ?? "",
            cw: (root["cw"] as? String) ?? "",
            slots: slots,
            rows: rows,
            ok: (counts["ok"] as? Int) ?? 0,
            warn: (counts["warn"] as? Int) ?? 0,
            blocked: (counts["blocked"] as? Int) ?? 0,
            queueCount: (root["queueCount"] as? Int) ?? 0
        )
    }

    // ── Previews (sequential; persists after each variant) ─────────

    func generatePreviews() async {
        guard let plan, let doc = docJson, let id = MassBatchFiles.batchId(of: doc) else { return }
        let targets = plan.rows.filter { $0.queueable }
        let client = self.client
        progress = (0, targets.count)
        generating = true
        for row in targets {
            guard let source = sourceImage(batchId: id, row: row) else {
                message = "Row \(row.index): image unreadable — preview skipped"
                continue
            }
            let projectJson = row.projectJson
            do {
                let png = try await Task.detached(priority: .userInitiated) {
                    try MemeRaster.renderPngData(asset: source, projectJson: projectJson, client: client)
                }.value
                guard let image = UIImage(data: png) else { continue }
                let poster = Self.posterJpeg(image)
                files.writePoster(id, rowId: row.id, jpeg: poster)
                let hash = SHA256.hash(data: poster).map { String(format: "%02x", $0) }.joined()
                op(#"{"op":"withRender","row":"\#(row.id)","posterName":"\#(MassBatchFiles.posterName(row.id))","posterHash":"\#(hash)"}"#)
            } catch {
                message = "Row \(row.index): preview failed"
            }
            progress = (progress.0 + 1, targets.count)
        }
        generating = false
        phase = .review
    }

    /// Per-event publish machine: one variant at a time (render →
    /// hash-verified Blossom upload → kind-20 sign), state persisted
    /// around every step so a kill mid-queue resumes cleanly.
    func runPublishQueue(identity: IdentityStore, publisher: NotePublisher, bridge: BusinessCoreBridge) async {
        publishing = true
        message = nil
        defer { publishing = false }
        while let plan, let doc = docJson, let id = MassBatchFiles.batchId(of: doc) {
            guard let row = plan.queue.first else { break }
            op(#"{"op":"withPublish","row":"\#(row.id)","state":"publishing"}"#)
            var ok = false
            if let source = sourceImage(batchId: id, row: row) {
                let client = self.client
                let projectJson = row.projectJson
                do {
                    let png = try await Task.detached(priority: .userInitiated) {
                        try MemeRaster.renderPngData(asset: source, projectJson: projectJson, client: client)
                    }.value
                    let size = (png.count, Int(source.size.width), Int(source.size.height))
                    let uploaded = try await BlossomUploader(bridge: bridge).upload(
                        bytes: png, mimeType: "image/png", identity: identity,
                        serverUrl: "https://blossom.primal.net"
                    )
                    let alt = Self.altText(of: row.projectJson)
                    await publisher.publishMemePictureNote(
                        caption: row.caption,
                        altText: alt,
                        contentWarningReason: plan.cw.isEmpty ? nil : plan.cw,
                        url: uploaded.url, hash: uploaded.hash,
                        size: size.0, width: size.1, height: size.2
                    )
                    ok = true
                } catch {
                    message = "Variant \(row.index): \(error.localizedDescription)"
                }
            } else {
                message = "Variant \(row.index): image unreadable"
            }
            op(#"{"op":"withPublish","row":"\#(row.id)","state":"\#(ok ? "published" : "failed")"\#(ok ? "" : ",\"failure\":\"upload or publish failed — retry available\"")}"#)
            if !ok { message = "Variant \(row.index) failed — remaining variants stay queued." }
        }
    }

    private func sourceImage(batchId: String, row: RowUi) -> UIImage? {
        let override = row.assetMap.values.first.flatMap { files.assetURL(batchId, file: $0) }
        let url = override ?? files.masterURL(batchId)
        return url.flatMap { UIImage(contentsOfFile: $0.path) }
    }

    private static func altText(of projectJson: String) -> String {
        guard let data = projectJson.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
        return (root["alt"] as? String) ?? ""
    }

    private static func posterJpeg(_ image: UIImage, maxEdge: CGFloat = 256) -> Data {
        let scale = min(1, maxEdge / max(image.size.width, image.size.height))
        let sized: UIImage
        if scale < 1 {
            let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            sized = UIGraphicsImageRenderer(size: size, format: format).image { _ in
                image.draw(in: CGRect(origin: .zero, size: size))
            }
        } else {
            sized = image
        }
        return sized.jpegData(compressionQuality: 0.8) ?? Data()
    }

    /// Newest batch's hub progress (nil = no batches).
    static func summaryOfNewestBatch() -> MassBatchSummary? {
        let files = MassBatchFiles()
        guard let newest = files.listBatches().first,
              let doc = files.load(newest.id) else { return nil }
        return massSummary(of: doc)
    }

    /// Deleting a batch from the picker bumps the observed revision so
    /// the list recomputes (@Observable has no stored-list here).
    private(set) var pickRevision = 0

    func deleteBatch(_ batchId: String) {
        files.delete(batchId)
        pickRevision += 1
    }
}

/// The batch flow surface: pick → setup (scr-batch) → review
/// (scr-review) → publish (scr-pub), replacing the hub while active.
private struct MassBatchAreaView: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    let flow: MassBatchFlow
    let onExit: () -> Void

    var body: some View {
        Group {
            if flow.docJson == nil || flow.phase == .pick {
                MassPickView(flow: flow, onExit: onExit)
            } else if flow.phase == .setup {
                MassSetupView(flow: flow)
            } else if flow.phase == .review {
                MassReviewView(flow: flow)
            } else {
                MassPublishView(flow: flow)
            }
        }
        .background(BitOSTheme.background)
    }
}

/// Batch picker: existing batches + New batch (master image pick).
private struct MassPickView: View {
    let flow: MassBatchFlow
    let onExit: () -> Void
    @State private var masterItem: PhotosPickerItem?

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.md) {
            HStack {
                Text("Mass production").font(.title2.weight(.bold))
                Spacer()
                Button("Done", action: onExit)
            }
            .padding(.horizontal, BitOSTheme.Spacing.md)

            PhotosPicker(selection: $masterItem, matching: .images) {
                Text("New batch — pick a master image")
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(BitOSTheme.accent)
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md))
            }
            .padding(.horizontal, BitOSTheme.Spacing.md)
            .onChange(of: masterItem) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        flow.create(masterData: data)
                    } else {
                        flow.message = "That image could not be read — pick a PNG or JPEG."
                    }
                    masterItem = nil
                }
            }

            if let message = flow.message {
                Text(message).font(.caption).foregroundStyle(BitOSTheme.error)
            }
            Text(
                "One recipe becomes many reviewable outputs. Pick a master image, add typed rows (or import a CSV), review every variant, publish only what you approve."
            )
            .font(.caption)
            .foregroundStyle(BitOSTheme.textSecondary)
            .padding(.horizontal, BitOSTheme.Spacing.md)

            List {
                let _ = flow.pickRevision
                let batches = flow.files.listBatches()
                if batches.isEmpty {
                    Text("No batches yet — pick a master image above to start your first run.")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                ForEach(batches, id: \.id) { entry in
                    Button {
                        if let doc = flow.files.load(entry.id) { flow.open(doc) }
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(entry.name).font(.subheadline.weight(.semibold))
                                Text(SlotRow.relativeTime(Int64(entry.updatedAt)))
                                    .font(.caption)
                                    .foregroundStyle(BitOSTheme.textSecondary)
                            }
                            Spacer()
                            Text("Open")
                                .font(.caption.weight(.bold))
                                .foregroundStyle(BitOSTheme.accent)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Open batch \(entry.name)")
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            flow.deleteBatch(entry.id)
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
        }
    }
}

/// Setup (scr-batch): recipe fields, typed slot rows, CSV import,
/// validity chips, generate-previews action.
private extension MassSetupView {
    /// Human summary of the dry-run CSV analysis (MUX-07).
    static func csvPreviewMessage(
        _ pending: (text: String, rows: Int, unknown: [String], missing: [String], overCap: Bool)
    ) -> String {
        if pending.overCap {
            return "\(pending.rows) rows found — the cap is 100 per import. Split the CSV and import in parts."
        }
        var lines: [String] = []
        if !pending.missing.isEmpty {
            lines.append("Missing required columns: \(pending.missing.joined(separator: ", ")) — those rows stay blocked until fixed.")
        }
        if !pending.unknown.isEmpty {
            lines.append("Ignored (no matching field): \(pending.unknown.joined(separator: ", ")).")
        }
        if lines.isEmpty {
            lines.append("Every column maps to a field. Nothing changes until you confirm.")
        }
        return lines.joined(separator: "\n")
    }
}

private struct MassSetupView: View {
    let flow: MassBatchFlow
    @State private var csvNotes = ""
    @State private var showCsvImporter = false
    /** MUX-07: analyzed import awaiting confirmation. */
    @State private var csvPending: (text: String, rows: Int, unknown: [String], missing: [String], overCap: Bool)?
    @State private var assetTarget: (row: String, slot: String)?
    @State private var assetItem: PhotosPickerItem?
    @State private var namingDraft = ""
    @State private var captionDraft = ""

    var body: some View {
        guard let plan = flow.plan else { return AnyView(EmptyView()) }
        return AnyView(VStack(spacing: 0) {
            HStack {
                Button { flow.close() } label: { Image(systemName: "xmark") }
                    .accessibilityLabel("Back to batches")
                VStack(alignment: .leading, spacing: 2) {
                    Text(plan.name).font(.headline)
                    HStack(spacing: 4) {
                        Text("recipe v\(plan.recipeVersion)")
                        if plan.frozen {
                            AppIcons.image(for: AppIcons.lock)
                            Text("edits fork v\(plan.recipeVersion + 1)")
                        }
                    }
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textSecondary)
                }
                Spacer()
            }
            .padding(.horizontal, BitOSTheme.Spacing.md)
            .padding(.vertical, BitOSTheme.Spacing.xs)

            List {
                Section {
                    TextField("e.g. meme-{i}.png", text: $namingDraft)
                        .onSubmit { flow.editRecipe(naming: namingDraft) }
                    TextField("e.g. gm {name} — {sats} sats", text: $captionDraft)
                        .onSubmit { flow.editRecipe(caption: captionDraft) }
                    Text("Placeholders: {i} row order · {name} · {sats} — every row renders one variant. Edits apply when you tap Generate (or press return).")
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                .onAppear {
                    namingDraft = plan.naming
                    captionDraft = plan.caption
                }

                Section("Rows") {
                    ForEach(plan.rows) { row in
                        MassRowEditor(row: row, slots: plan.slots, flow: flow,
                                      pickAsset: { assetTarget = ($0.row, $0.slot) })
                    }
                    .onDelete { offsets in
                        for index in offsets where index < plan.rows.count {
                            flow.removeRow(plan.rows[index].id)
                        }
                    }
                    Button {
                        flow.addRow()
                    } label: {
                        Label("Add row", systemImage: "plus")
                    }
                    Button {
                        showCsvImporter = true
                    } label: {
                        Label("Import CSV", systemImage: "square.and.arrow.down")
                    }
                }
                if !csvNotes.isEmpty {
                    Section {
                        Text(csvNotes).font(.caption).foregroundStyle(BitOSTheme.textSecondary)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)

            VStack(spacing: BitOSTheme.Spacing.xs) {
                HStack(spacing: BitOSTheme.Spacing.xs) {
                    MassSeverityChip(label: "\(plan.ok) valid", color: .green)
                    MassSeverityChip(label: "\(plan.warn) warnings", color: .orange)
                    if plan.blocked > 0 {
                        MassSeverityChip(label: "\(plan.blocked) blocked — excluded", color: .red)
                    }
                }
                Button {
                    flow.editRecipe(naming: namingDraft)
                    flow.editRecipe(caption: captionDraft)
                    Task { await flow.generatePreviews() }
                } label: {
                    HStack {
                        if flow.generating { ProgressView().tint(.white) }
                        Text(
                            flow.generating
                                ? "Rendering \(flow.progress.0)/\(flow.progress.1)"
                                : "Generate previews (\(plan.queueableCount))"
                        )
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(plan.queueableCount == 0 || flow.generating)
            }
            .padding(BitOSTheme.Spacing.md)
        }
        .fileImporter(
            isPresented: $showCsvImporter,
            allowedContentTypes: [UTType(filenameExtension: "csv") ?? .plainText, .plainText]
        ) { result in
            if case .success(let url) = result, let text = try? String(contentsOf: url, encoding: .utf8),
               let doc = flow.docJson,
               let data = flow.client.massBatchCsvPreview(doc, csv: text).data(using: .utf8),
               let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                // MUX-07: dry-run analysis first — nothing imports until confirmed.
                csvPending = (
                    text,
                    (root["dataRows"] as? NSNumber)?.intValue ?? 0,
                    (root["unknownColumns"] as? [String]) ?? [],
                    (root["missingRequired"] as? [String]) ?? [],
                    (root["overCap"] as? Bool) ?? false
                )
            } else if case .success(let url) = result, let text = try? String(contentsOf: url, encoding: .utf8) {
                csvNotes = "The CSV could not be analyzed — check its encoding and try again."
            }
        }
        .confirmationDialog(
            csvPending?.overCap == true ? "Too many rows" : "Import \(csvPending?.rows ?? 0) rows?",
            isPresented: Binding(
                get: { csvPending != nil },
                set: { if !$0 { csvPending = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let pending = csvPending, !pending.overCap {
                Button("Import \(pending.rows) rows") {
                    flow.importCsv(pending.text)
                    csvNotes = flow.message ?? ""
                    csvPending = nil
                }
            }
            Button("Cancel", role: .cancel) { csvPending = nil }
        } message: {
            if let pending = csvPending {
                Text(Self.csvPreviewMessage(pending))
            }
        }        .photosPicker(
            isPresented: Binding(
                get: { assetTarget != nil && assetItem == nil },
                set: { if !$0 { assetTarget = nil } }
            ),
            selection: $assetItem,
            matching: .images
        )
        .onChange(of: assetItem) { _, item in
            guard let target = assetTarget else { return }
            if let item {
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        flow.setAsset(rowId: target.row, slotId: target.slot, data: data)
                    }
                    assetItem = nil
                    assetTarget = nil
                }
            }
        })
    }
}

private struct MassRowEditor: View {
    let row: MassBatchFlow.RowUi
    let slots: [MassBatchFlow.SlotUi]
    let flow: MassBatchFlow
    let pickAsset: ((row: String, slot: String)) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
            Text("#\(row.index)")
                .font(.caption.weight(.bold))
                .foregroundStyle(BitOSTheme.textSecondary)
            ForEach(slots) { slot in
                if slot.isAsset {
                    HStack {
                        Text(slot.name + (slot.required ? "" : " (optional)"))
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.textSecondary)
                        Spacer()
                        Button(row.assets[slot.id] != nil ? "replace image" : "pick image") {
                            pickAsset((row.id, slot.id))
                        }
                        .font(.caption)
                        if row.assets[slot.id] != nil {
                            AppIcons.image(for: AppIcons.checkCircle)
                                .foregroundStyle(.green)
                                .font(.caption)
                        }
                    }
                } else {
                    TextField(
                        slot.name + (slot.required ? "" : " (optional)")
                            + " · " + slotHint(slot),
                        text: Binding(
                            get: { row.values[slot.id] ?? "" },
                            set: { flow.setValue(rowId: row.id, slotId: slot.id, value: $0) }
                        )
                    )
                    .font(.subheadline)
                }
            }
            ForEach(row.notes, id: \.self) { note in
                Text("· \(note)")
                    .font(.caption2)
                    .foregroundStyle(row.blocked ? Color.red : Color.orange)
            }
        }
        .padding(.vertical, 2)
    }

    private func slotHint(_ slot: MassBatchFlow.SlotUi) -> String {
        switch slot.type {
        case "number": return "number (1k works)"
        case "color": return "#rrggbb"
        case "timestamp": return "timestamp"
        default: return slot.enumValues.isEmpty ? "text" : slot.enumValues.joined(separator: "/")
        }
    }
}

/// Review contact sheet (scr-review): stable order, per-tile approval.
private struct MassReviewView: View {
    let flow: MassBatchFlow
    private let columns = [GridItem(.adaptive(minimum: 100), spacing: BitOSTheme.Spacing.sm)]
    @State private var showApproveAll = false
    @State private var selected: Set<String> = []

    var body: some View {
        guard let plan = flow.plan else { return AnyView(EmptyView()) }
        let approvedCount = plan.queue.count
        let publishedCount = plan.rows.filter { $0.publish == "published" }.count
        return AnyView(VStack(spacing: 0) {
            HStack {
                Button("Setup") { flow.phase = .setup }
                    .font(.subheadline)
                Spacer()
                Text("Review · \(plan.rows.count) variants")
                    .font(.headline)
            }
            .padding(.horizontal, BitOSTheme.Spacing.md)
            .padding(.vertical, BitOSTheme.Spacing.xs)

            ScrollView {
                LazyVGrid(columns: columns, spacing: BitOSTheme.Spacing.sm) {
                    ForEach(plan.rows) { row in
                        MassVariantTile(row: row, flow: flow, selected: $selected)
                    }
                }
                .padding(.horizontal, BitOSTheme.Spacing.md)
            }

            VStack(spacing: BitOSTheme.Spacing.xs) {
                Text(
                    "\(approvedCount) approved · \(publishedCount) published · \(plan.blocked) excluded (blocked)"
                )
                .font(.caption)
                .foregroundStyle(BitOSTheme.textSecondary)
                // MUX-09: export selection is DISTINCT from publish approval.
                HStack(spacing: BitOSTheme.Spacing.xs) {
                    Button(selected.count == plan.ok ? "Deselect all" : "Select all ready") {
                        selected = selected.count == plan.ok ? [] : Set(plan.rows.filter(\.queueable).map(\.id))
                    }
                    .font(.caption)
                    .disabled(plan.ok == 0)
                    Text("· \(plan.blocked) blocked excluded")
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.textSecondary)
                    Spacer()
                    Text("\(selected.count) chosen")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(BitOSTheme.accent)
                }
                HStack(spacing: BitOSTheme.Spacing.sm) {
                    Button("Approve all valid") { showApproveAll = true }
                        .buttonStyle(.bordered)
                    Button("Sign & publish \(approvedCount)") {
                        flow.phase = .publish
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(approvedCount == 0 || flow.publishing)
                }
                HStack(spacing: BitOSTheme.Spacing.sm) {
                    Button {
                        Task { await flow.exportSelected(Array(selected)) }
                    } label: {
                        HStack {
                            if flow.exportingBatch { ProgressView() }
                            Text("Export selected (\(selected.count))")
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(BitOSTheme.accent)
                    .disabled(selected.isEmpty || flow.exportingBatch)
                    if !flow.exportFailedIds.isEmpty {
                        Button("Retry failed (\(flow.exportFailedIds.count))") {
                            Task { await flow.exportSelected(flow.exportFailedIds) }
                        }
                        .buttonStyle(.bordered)
                        .disabled(flow.exportingBatch)
                    }
                }
                if !flow.exportResults.isEmpty {
                    let saved = flow.exportResults.values.filter { $0.isEmpty }.count
                    Text(
                        saved == flow.exportResults.count
                            ? "\(saved) saved to Photos ✓"
                            : "\(saved) saved · \(flow.exportResults.count - saved) need attention"
                    )
                    .font(.caption)
                    .foregroundStyle(saved == flow.exportResults.count ? BitOSTheme.success : BitOSTheme.warning)
                }
                Text("Nothing signs until each upload hash-verifies. Approvals bind content hashes — edits invalidate.")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            .padding(BitOSTheme.Spacing.md)
        }
        .sheet(isPresented: Binding(
            get: { flow.previewingRowId != nil },
            set: { if !$0 { flow.previewingRowId = nil } }
        )) {
            if let rowId = flow.previewingRowId,
               let row = plan.rows.first(where: { $0.id == rowId }) {
                MassVariantPreviewView(flow: flow, rows: plan.rows, row: row)
            }
        }
        .confirmationDialog(
            "Approve \(plan.ok) valid variants?",
            isPresented: $showApproveAll,
            titleVisibility: .visible
        ) {
            Button("Approve \(plan.ok)") { flow.approveAll() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(
                "\(plan.warn) with warnings included · \(plan.blocked) excluded (blocked) · changed content goes back to unapproved."
            )
        })
    }
}

/// MUX-08 full variant preview: aspect-fit poster, Previous/Next, explicit
/// approve control and an "Edit this version" jump into setup. Inspecting
/// never approves.
private struct MassVariantPreviewView: View {
    @Environment(\.dismiss) private var dismiss
    let flow: MassBatchFlow
    let rows: [MassBatchFlow.RowUi]
    let row: MassBatchFlow.RowUi

    var body: some View {
        let poster = flow.docJson.flatMap { MassBatchFiles.batchId(of: $0) }
            .flatMap { flow.files.posterURL($0, rowId: row.id) }
            .flatMap { UIImage(contentsOfFile: $0.path) }
        VStack(spacing: BitOSTheme.Spacing.md) {
            HStack {
                Text("Variant #\(row.index)")
                    .font(.headline)
                Spacer()
                Button("Done") { dismiss() }
            }
            Spacer(minLength: 0)
            Group {
                if let poster {
                    Image(uiImage: poster)
                        .resizable()
                        .scaledToFit()
                } else {
                    Text(row.blocked ? (row.notes.first ?? "blocked — fix the row in Setup") : "queued — generate previews first")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(BitOSTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md))
            Spacer(minLength: 0)
            if !row.notes.isEmpty {
                Text(row.notes.joined(separator: " · "))
                    .font(.caption2)
                    .foregroundStyle(row.blocked ? BitOSTheme.error : BitOSTheme.warning)
            }
            HStack(spacing: BitOSTheme.Spacing.sm) {
                Button {
                    if let index = rows.firstIndex(where: { $0.id == row.id }), index > 0 {
                        flow.previewingRowId = rows[index - 1].id
                    }
                } label: {
                    Image(systemName: "chevron.left")
                }
                .disabled(rows.first?.id == row.id)
                .accessibilityLabel("Previous variant")
                Button(row.approved ? "Unapprove" : "Approve") {
                    if !row.blocked { flow.approve(row.id, !row.approved) }
                }
                .buttonStyle(.borderedProminent)
                .tint(row.approved ? BitOSTheme.error : BitOSTheme.accent)
                .disabled(row.blocked)
                Button("Edit this version") {
                    dismiss()
                    flow.phase = .setup
                }
                .buttonStyle(.bordered)
                Button {
                    if let index = rows.firstIndex(where: { $0.id == row.id }),
                       index + 1 < rows.count {
                        flow.previewingRowId = rows[index + 1].id
                    }
                } label: {
                    Image(systemName: "chevron.right")
                }
                .disabled(rows.last?.id == row.id)
                .accessibilityLabel("Next variant")
            }
        }
        .padding(BitOSTheme.Spacing.md)
        .background(BitOSTheme.background)
    }
}

private struct MassVariantTile: View {
    let row: MassBatchFlow.RowUi
    let flow: MassBatchFlow
    @Binding var selected: Set<String>

    var body: some View {
        let poster = flow.docJson.flatMap { MassBatchFiles.batchId(of: $0) }
            .flatMap { flow.files.posterURL($0, rowId: row.id) }
            .flatMap { UIImage(contentsOfFile: $0.path) }
        Button {
            // MUX-08: inspecting never approves — the body opens the preview.
            flow.previewingRowId = row.id
        } label: {
            VStack(spacing: 4) {
                ZStack {
                    Rectangle()
                        .fill(BitOSTheme.surface)
                        .aspectRatio(0.75, contentMode: .fit)
                    if let poster {
                        Image(uiImage: poster)
                            .resizable()
                            .scaledToFill()
                            .frame(minWidth: 0, maxWidth: .infinity, minHeight: 0)
                            .clipped()
                    } else {
                        Text("queued")
                            .font(.caption2)
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                    VStack {
                        HStack {
                            Text("#\(row.index)")
                                .font(.system(size: 9, weight: .bold))
                                .padding(.horizontal, 5).padding(.vertical, 1)
                                .background(Color.black.opacity(0.55))
                                .foregroundStyle(.white)
                                .clipShape(Capsule())
                            Spacer()
                            // MUX-09: export selection (distinct from approval).
                            Button {
                                if selected.contains(row.id) {
                                    selected.remove(row.id)
                                } else if row.queueable {
                                    selected.insert(row.id)
                                }
                            } label: {
                                Image(systemName: selected.contains(row.id) ? "checkmark.circle.fill" : "circle")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(selected.contains(row.id) ? BitOSTheme.accent : Color.white.opacity(0.85))
                                    .padding(4)
                            }
                            .buttonStyle(.plain)
                            .disabled(!row.queueable)
                            .accessibilityLabel(selected.contains(row.id) ? "Remove variant \(row.index) from export" : "Add variant \(row.index) to export")
                            if row.severity == "warn" {
                                AppIcons.image(for: AppIcons.warningTriangle)
                                    .font(.system(size: 9, weight: .heavy))
                                    .padding(4)
                                    .background(Color.orange)
                                    .foregroundStyle(.black)
                                    .clipShape(Capsule())
                            }
                        }
                        Spacer()
                        if row.publish == "published" {
                            HStack {
                                Text("live")
                                    .font(.system(size: 9, weight: .heavy))
                                    .padding(.horizontal, 5).padding(.vertical, 1)
                                    .background(Color.green.opacity(0.9))
                                    .foregroundStyle(.black)
                                    .cornerRadius(6)
                                Spacer()
                            }
                        }
                    }
                    .padding(4)
                }
                .frame(height: 116)
                .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))

                HStack(spacing: 4) {
                    Button {
                        // Explicit approval control, separate from inspection.
                        if !row.blocked { flow.approve(row.id, !row.approved) }
                    } label: {
                        AppIcons.image(for: row.approved ? AppIcons.checkCircle : "circle")
                            .font(.system(size: 14))
                            .foregroundStyle(row.approved ? Color.green : BitOSTheme.textSecondary)
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.plain)
                    .disabled(row.blocked)
                    .accessibilityLabel(row.approved ? "Unapprove variant \(row.index)" : "Approve variant \(row.index)")
                    Text(
                        row.blocked
                            ? (row.notes.first ?? "blocked")
                            : (row.values["name"] ?? row.id)
                    )
                    .font(.system(size: 10))
                    .lineLimit(1)
                    Spacer(minLength: 0)
                }
            }
            .opacity(row.blocked ? 0.5 : 1)
        }
        .buttonStyle(.plain)
    }
}

/// Publish machine (scr-pub): per-event signing, crash-safe advance.
private struct MassPublishView: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    let flow: MassBatchFlow
    @State private var started = false

    var body: some View {
        guard let plan = flow.plan else { return AnyView(EmptyView()) }
        return AnyView(VStack(spacing: 0) {
            HStack {
                Button("Review") {
                    flow.phase = .review
                    started = false
                }
                .font(.subheadline)
                Spacer()
                Text(flow.publishing ? "Publishing…" : "Publish queue")
                    .font(.headline)
            }
            .padding(.horizontal, BitOSTheme.Spacing.md)
            .padding(.vertical, BitOSTheme.Spacing.xs)

            if let message = flow.message {
                Text(message)
                    .font(.caption)
                    .foregroundStyle(Color.orange)
                    .padding(.horizontal, BitOSTheme.Spacing.md)
            }

            List {
                ForEach(plan.rows) { row in
                    HStack {
                        Text("#\(row.index)")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(BitOSTheme.textSecondary)
                            .frame(width: 30, alignment: .leading)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.values["name"] ?? row.id)
                                .font(.subheadline.weight(.semibold))
                                .lineLimit(1)
                            Text(statusText(row))
                                .font(.caption2)
                                .foregroundStyle(statusColor(row))
                        }
                        Spacer()
                        switch row.publish {
                        case "published":
                            AppIcons.image(for: AppIcons.checkCircle).foregroundStyle(.green)
                        case "publishing":
                            ProgressView().controlSize(.small)
                        default: EmptyView()
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)

            Text("Signing is per event — each variant uploads and hash-verifies before its own kind-20 signs.")
                .font(.caption2)
                .foregroundStyle(BitOSTheme.textSecondary)
                .padding(BitOSTheme.Spacing.md)
        }
        .task {
            guard !started, !flow.publishing else { return }
            started = true
            let bridge = (environment.businessCore as? FrameworkBusinessCoreClient)?
                .bridgeForFollowing() ?? BusinessCoreBridge()
            await flow.runPublishQueue(identity: identity, publisher: environment.notePublisher, bridge: bridge)
        })
    }

    private func statusText(_ row: MassBatchFlow.RowUi) -> String {
        switch row.publish {
        case "published": return "published"
        case "publishing": return "render → upload → sign…"
        case "failed": return "failed — will retry on next run"
        default: return "waiting"
        }
    }

    private func statusColor(_ row: MassBatchFlow.RowUi) -> Color {
        switch row.publish {
        case "published": return .green
        case "failed": return .red
        case "publishing": return BitOSTheme.accent
        default: return BitOSTheme.textSecondary
        }
    }
}

private struct MassSeverityChip: View {
    let label: String
    let color: Color

    var body: some View {
        Text(label)
            .font(.system(size: 10, weight: .bold))
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(color.opacity(0.14))
            .clipShape(Capsule())
    }
}
