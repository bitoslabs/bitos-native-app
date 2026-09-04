import SwiftUI

/// Identifiable wrapper so sheets can present a raw JSON string by value.
struct RawEvent: Identifiable {
    let value: String
    var id: String { String(value.hashValue) }
}

/// Raw relay-event JSON viewer (card ⋯ "View raw event JSON" + inbox rows,
/// web parity): monospaced canonical form, close chrome. The text shown for
/// feed notes is the NIP-01 canonical event object from the shared codec.
struct RawEventSheet: View {
    let text: String
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
            }
        }
        .preferredColorScheme(BitOSTheme.preferredScheme)
    }
}
