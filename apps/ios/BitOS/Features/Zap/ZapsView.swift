import BusinessCore
import SwiftUI

/**
 * APP-014 zap wallet (legacy `ZapsView` / web /zaps parity): stat tiles
 * (Received · Sent · Avg · Net) above an All/Received/Sent ledger. Sent =
 * the local device ledger; received = verified kind-9735 receipts from the
 * notification stream. Merge + totals rules run in shared
 * `SentZapLedger` through the bridge.
 */
struct ZapsView: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    @State private var sentZaps = SentZapsStore()
    @State private var tab = 0
    let onClose: () -> Void

    struct LedgerRow: Identifiable, Equatable {
        let id: String
        let direction: String // received | sent
        let sats: Int64
        let peer: String
        let at: Int64
        let memo: String
        let note: String
    }

    /// Entries JSON (bridge `zapLedgerEntries`) → rows. Shared by the
    /// wallet and the profile Zaps tab — one decode, no fork.
    static func decodeLedgerRows(_ entriesJson: String) -> [LedgerRow] {
        guard let data = entriesJson.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return array.enumerated().compactMap { index, obj in
            guard let direction = obj["direction"] as? String,
                  let sats = (obj["sats"] as? NSNumber)?.int64Value,
                  let peer = obj["peer"] as? String,
                  let at = (obj["at"] as? NSNumber)?.int64Value else { return nil }
            return LedgerRow(
                id: "\(index)-\(direction)-\(sats)-\(at)",
                direction: direction, sats: sats, peer: peer, at: at,
                memo: (obj["memo"] as? String) ?? "",
                note: (obj["note"] as? String) ?? ""
            )
        }
    }

    private var receivedItems: [NotificationItem] {
        environment.inboxStore.items.filter { $0.kind == .zap }
    }

    private var entries: [LedgerRow] {
        let sent = sentZaps.records.map { record -> [String: Any] in
            var obj: [String: Any] = [
                "id": record.id, "sats": record.amountSats,
                "to": record.recipientPubkey, "at": record.createdAt,
            ]
            if let note = record.targetNoteId { obj["note"] = note }
            if let memo = record.memo { obj["memo"] = memo }
            return obj
        }
        let received = receivedItems.map { item -> [String: Any] in
            var obj: [String: Any] = [
                "sats": (item.amountMsat ?? 0) / 1000,
                "from": item.authorPubkey,
                "at": item.createdAt,
            ]
            if let note = item.targetEventId { obj["note"] = note }
            return obj
        }
        guard let sentData = try? JSONSerialization.data(withJSONObject: sent),
              let sentJson = String(data: sentData, encoding: .utf8),
              let receivedData = try? JSONSerialization.data(withJSONObject: received),
              let receivedJson = String(data: receivedData, encoding: .utf8) else { return [] }
        let bridge = BusinessCoreBridge()
        guard let entriesJson = (bridge.zapLedgerEntries(sentRecordsJson: sentJson, receivedJson: receivedJson) as String?) else { return [] }
        return Self.decodeLedgerRows(entriesJson)
    }

    private var totals: (received: Int64, sent: Int64, avg: Int64, net: Int64) {
        let bridge = BusinessCoreBridge()
        let rows = entries.map { row -> [String: Any] in
            ["direction": row.direction, "sats": row.sats, "peer": row.peer, "at": row.at, "memo": row.memo, "note": row.note]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: rows),
              let json = String(data: data, encoding: .utf8),
              let totalsJson = (bridge.zapLedgerTotals(entriesJson: json) as String?),
              let tData = totalsJson.data(using: .utf8),
              let t = try? JSONSerialization.jsonObject(with: tData) as? [String: Any] else {
            return (0, 0, 0, 0)
        }
        return (
            (t["received"] as? NSNumber)?.int64Value ?? 0,
            (t["sent"] as? NSNumber)?.int64Value ?? 0,
            (t["avg"] as? NSNumber)?.int64Value ?? 0,
            (t["net"] as? NSNumber)?.int64Value ?? 0
        )
    }

    var body: some View {
        NavigationStack {
            Group {
                if identity.account == nil {
                    Text("Add an identity (You tab) to see your zap history.")
                        .font(.footnote)
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ledgerContent
                }
            }
            .background(BitOSTheme.background)
            .navigationTitle("Zap wallet")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    SheetCloseButton(action: onClose)
                }
            }
        }
        .preferredColorScheme(BitOSTheme.preferredScheme)
    }

    private var ledgerContent: some View {
        let all = entries
        let filtered = tab == 1 ? all.filter { $0.direction == "received" } :
                       tab == 2 ? all.filter { $0.direction == "sent" } : all
        let t = totals
        let bridge = BusinessCoreBridge()
        return VStack(spacing: BitOSTheme.Spacing.sm) {
            HStack(spacing: BitOSTheme.Spacing.sm) {
                statTile("Received", bridge.zapFormatSats(sats: t.received), emphasized: true)
                statTile("Sent", bridge.zapFormatSats(sats: t.sent))
                statTile("Avg", bridge.zapFormatSats(sats: t.avg))
                statTile("Net", bridge.zapFormatSats(sats: t.net))
            }
            .padding(.horizontal, BitOSTheme.Spacing.screen)
            HStack(spacing: BitOSTheme.Spacing.md) {
                ForEach(Array(["All", "Received", "Sent"].enumerated()), id: \.offset) { index, label in
                    Button(label) { tab = index }
                        .font(.system(size: 13, weight: tab == index ? .heavy : .semibold))
                        .foregroundStyle(tab == index ? BitOSTheme.accent : BitOSTheme.textSecondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, BitOSTheme.Spacing.screen)
            if filtered.isEmpty {
                VStack(spacing: BitOSTheme.Spacing.md) {
                    Text("⚡").font(.system(size: 34))
                    Text("No zaps yet")
                        .font(.system(size: 13))
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(filtered) { row in
                    ZapLedgerRowView(row: row, profile: environment.feedStore.profiles[row.peer])
                        .listRowBackground(BitOSTheme.surface)
                        .listRowSeparatorTint(BitOSTheme.divider)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
    }

    private func statTile(_ label: String, _ value: String, emphasized: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.system(size: 10))
                .foregroundStyle(BitOSTheme.textTertiary)
            Text(value)
                .font(.system(size: 15, weight: .heavy))
                .foregroundStyle(emphasized ? BitOSTheme.zap : BitOSTheme.textPrimary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(emphasized ? BitOSTheme.zap.opacity(0.10) : BitOSTheme.surfaceElevated)
        )
    }
}

struct ZapLedgerRowView: View {
    let row: ZapsView.LedgerRow
    let profile: ProfileMetadata?
    private let bridge = BusinessCoreBridge()

    private var received: Bool { row.direction == "received" }

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.md) {
            ZStack {
                Circle().fill(received ? BitOSTheme.zap.opacity(0.12) : BitOSTheme.surfaceElevated)
                Text("⚡").font(.system(size: 13))
            }
            .frame(width: 34, height: 34)
            PubkeyAvatarView(pubkey: row.peer, size: 34)
            VStack(alignment: .leading, spacing: 1) {
                Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(row.peer))
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
                Text([received ? "Received" : "Sent", row.memo.isEmpty ? nil : row.memo].compactMap { $0 }.joined(separator: " · "))
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .lineLimit(1)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 1) {
                Text("\(received ? "+" : "−")\(bridge.zapFormatSats(sats: row.sats))")
                    .font(.system(size: 13, weight: .heavy))
                    .foregroundStyle(received ? BitOSTheme.zap : BitOSTheme.textSecondary)
                Text(FeedFormat.timeAgo(createdAt: row.at))
                    .font(.system(size: 10))
                    .foregroundStyle(BitOSTheme.textTertiary)
            }
        }
        .padding(.vertical, 4)
    }
}
