import Foundation
import Security

/// Minimal Keychain wrapper for the identity secret (ID-002 skeleton).
/// kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly: sealed at rest,
/// excluded from backups by device scoping, never in iCloud keychain.
enum IdentityKeychain {
    private static let service = "space.bitos.identity"
    private static let account = "local-signer"
    private static let slotPrefix = "local-signer-"

    static func save(secretHex: String) -> Bool {
        let data = Data(secretHex.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
        var attributes = query
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess
    }

    static func loadSecret() -> String? {
        load(account: account)
    }

    // ── Multi-account slots (APP-018a row 1) ──────────────────────

    /// Seals a secret under its pubkey-keyed slot.
    static func save(secretHex: String, slotPubkey: String) -> Bool {
        write(secretHex: secretHex, account: slotPrefix + slotPubkey)
    }

    static func loadSecret(slotPubkey: String) -> String? {
        load(account: slotPrefix + slotPubkey)
    }

    /// Pubkeys that currently have a sealed slot.
    static func slotPubkeys() -> [String] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let items = result as? [[String: Any]] else { return [] }
        // Split for the type-checker (§ prior pagerPage/pagerPosition fixes).
        let accounts: [String?] = items.map { item in
            item[kSecAttrAccount as String] as? String
        }
        return accounts.compactMap { account -> String? in
            guard let account, account.hasPrefix(slotPrefix) else { return nil }
            let suffix = account.dropFirst(slotPrefix.count)
            guard suffix.count == 64, suffix.allSatisfy({ $0.isHexDigit }) else { return nil }
            return String(suffix)
        }
    }

    /// Destructively wipes one account slot.
    static func removeSlot(pubkey: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: slotPrefix + pubkey,
        ]
        SecItemDelete(query as CFDictionary)
    }

    static func clear() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }

    private static func write(secretHex: String, account key: String) -> Bool {
        let data = Data(secretHex.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
        var attributes = query
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess
    }

    private static func load(account key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
