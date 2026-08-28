import SwiftUI

/// Profile editing sheet: bounded fields → signed kind-0 through the
/// receipt machine.
struct ProfileEditSheet: View {
    let publisher: NotePublisher
    let onClose: () -> Void
    @Environment(IdentityStore.self) private var identity
    @State private var name = ""
    @State private var displayName = ""
    @State private var about = ""
    @State private var nip05 = ""
    @State private var lud16 = ""
    @State private var published = false

    var body: some View {
        NavigationStack {
            content
                .padding(BitOSTheme.Spacing.screen)
                .background(BitOSTheme.background)
                .navigationTitle("Edit profile")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Close") { onClose() } }
                }
        }
        .preferredColorScheme(.dark)
    }

    @ViewBuilder
    private var content: some View {
        VStack(spacing: BitOSTheme.Spacing.sm) {
            if published {
                Label { Text("Profile published ✓") } icon: { AppIcons.image(for: AppIcons.checkCircle) }
                    .foregroundStyle(BitOSTheme.success)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else if case .rejected(let detail) = publisher.result {
                Text(detail ?? "Relays rejected the profile.").font(.caption).foregroundStyle(BitOSTheme.error)
            }

            TextField("Name", text: $name)
                .textFieldStyle(.roundedBorder)
            TextField("Display name", text: $displayName)
                .textFieldStyle(.roundedBorder)
            TextField("About", text: $about)
                .textFieldStyle(.roundedBorder)
                .lineLimit(3)
            TextField("NIP-05 (user@domain)", text: $nip05)
                .textFieldStyle(.roundedBorder)
            TextField("Lightning address (user@domain)", text: $lud16)
                .textFieldStyle(.roundedBorder)

            Text("Publishes a signed profile event to your relays; the change appears once confirmed.")
                .font(.caption2)
                .foregroundStyle(BitOSTheme.textTertiary)
                .frame(maxWidth: .infinity, alignment: .leading)

            Button(publisher.busy ? "Publishing…" : "Publish profile") {
                Task {
                    await publisher.publishProfile(
                        name: name, displayName: displayName, about: about,
                        picture: "", nip05: nip05, lud16: lud16
                    )
                    published = true
                }
            }
            .buttonStyle(.borderedProminent)
            .tint(BitOSTheme.accent)
            .disabled(publisher.busy || name.isEmpty && displayName.isEmpty)

            Spacer()
        }
    }
}
