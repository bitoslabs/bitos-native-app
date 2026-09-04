import SwiftUI

/// Identifiable wrapper so sheets can present a raw JSON string by value.
struct RawEvent: Identifiable {
    let value: String
    let isEventJson: Bool

    init(value: String, isEventJson: Bool = true) {
        self.value = value
        self.isEventJson = isEventJson
    }

    var id: String { String(value.hashValue) }
}

/// Raw relay-event JSON viewer (card ⋯ "View raw event JSON" + inbox rows,
/// web parity): monospaced canonical form, close chrome. The text shown for
/// feed notes is the NIP-01 canonical event object from the shared codec.
struct RawEventSheet: View {
    let text: String
    var isEventJson: Bool = true
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(text)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
            }
            .background(BitOSTheme.background)
            .navigationTitle("Raw event")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    SheetCloseButton { dismiss() }
                }
                if isEventJson {
                    ToolbarItem(placement: .primaryAction) {
                        Button("Copy event text") { UIPasteboard.general.string = text }
                    }
                }
            }
        }
        .preferredColorScheme(BitOSTheme.preferredScheme)
    }
}
