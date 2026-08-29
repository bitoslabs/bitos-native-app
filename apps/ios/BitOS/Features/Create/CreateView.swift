import SwiftUI

/// Create surface: fast paths into capture and the Studio. Camera, import
/// and editors are native-only features (CAP/EDT epics); the chooser is the
/// stable entry contract.
struct CreateView: View {
    @State private var showCamera = false
    @State private var showImport = false
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity

    private struct QuickAction: Identifiable {
        let id = UUID()
        let symbol: String
        let title: LocalizedStringKey
        let description: LocalizedStringKey
    }

    private let quickActions: [QuickAction] = [
        QuickAction(symbol: "camera.fill", title: "Record Bitz", description: "Record a portrait clip with segment control"),
        QuickAction(symbol: "photo.on.rectangle", title: "Import media", description: "Copy assets into the project catalog"),
        QuickAction(symbol: "face.smiling.inverse", title: "Quick MEM", description: "Caption, look and stickers in seconds"),
        QuickAction(symbol: "music.note", title: "Use a sound", description: "Start a project from a licensed sound"),
        QuickAction(symbol: "arrow.triangle.2.circlepath", title: "Remix", description: "Build on a template with attribution"),
    ]

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(Array(quickActions.enumerated()), id: \.element.id) { index, action in
                        HStack(spacing: BitOSTheme.Spacing.md) {
                            Image(systemName: action.symbol)
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
                        }
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            if index == 0 { showCamera = true }
                            if index == 1 { showImport = true }
                        }
                    }
                }
                Section {
                    VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
                        Text("Project library").font(.subheadline.weight(.semibold))
                        Text("Drafts live on-device by default. The library lists projects, storage usage and recovery state once the editor phase lands.")
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(BitOSTheme.background)
            .navigationTitle("Create")
            .fullScreenCover(isPresented: $showCamera) {
                CameraScreen(
                    onCaptured: { data, mime in
                        // Camera takes flow straight into the publish sheet state.
                        showCamera = false
                        showImport = true
                        capturedData = data
                        capturedMime = mime
                    },
                    onCancel: { showCamera = false }
                )
            }
            .sheet(isPresented: $showImport) {
                ImportMediaSheet(
                    onClose: {
                        showImport = false
                        capturedData = nil
                    },
                    capturedData: capturedData,
                    capturedMime: capturedMime
                )
                .environment(identity)
                .presentationDetents([.medium, .large])
            }
        }
    }

    @State private var capturedData: Data?
    @State private var capturedMime = "video/mp4"
}
