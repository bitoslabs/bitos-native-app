import BusinessCore
import Foundation
import Observation
import Security

/// Signer kind mirror (shared `SignerKind`).
enum PlatformSignerKind: Sendable, Equatable {
    case localKey
    case readOnly
}

/// Non-secret account projection for UI state.
struct AccountIdentity: Sendable, Equatable {
    let pubkeyHex: String
    let npub: String
    let signerKind: PlatformSignerKind
}

/// A pending identity awaiting explicit user confirmation (ID-004). The
/// secret exists in memory ONLY inside this transaction until confirmed.
struct IdentityPreview: Sendable, Equatable {
    let npub: String
    let secretHex: String
    let replacesExisting: Bool
}

@MainActor
@Observable
final class IdentityStore {
    private(set) var account: AccountIdentity?
    var preview: IdentityPreview?
    private(set) var importError: String?
    private(set) var busy = false

    private let bridge: BusinessCoreBridge

    init(bridge: BusinessCoreBridge = BusinessCoreBridge()) {
        self.bridge = bridge
        if let secret = IdentityKeychain.loadSecret() {
            account = identity(forSecret: secret)
        }
    }


    // MARK: - Account lifecycle

    /// Prepares a freshly generated key (CSPRNG) for confirmation.
    func createKeyPreview() {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, 32, &bytes)
        guard status == errSecSuccess else { return }
        let secretHex = bytes.map { String(format: "%02x", $0) }.joined()
        guard let identity = identity(forSecret: secretHex) else { return }
        preview = IdentityPreview(
            npub: identity.npub,
            secretHex: secretHex,
            replacesExisting: account != nil
        )
        importError = nil
    }

    /// Validates an nsec import and prepares it for confirmation.
    func importKeyPreview(_ input: String) {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let secret = bridge.parseNsec(encoded: trimmed) else {
            importError = bridge.parseNpub(encoded: trimmed) != nil
                ? "That is a public key (npub); import needs the secret (nsec)."
                : "Not a valid nsec key."
            return
        }
        guard let identity = identity(forSecret: secret) else {
            importError = "Key rejected by the signer."
            return
        }
        preview = IdentityPreview(
            npub: identity.npub,
            secretHex: secret,
            replacesExisting: account != nil
        )
        importError = nil
    }

    /// Stores the previewed identity; the visible npub is the confirmation.
    func confirmPreview() {
        guard let preview else { return }
        busy = true
        if IdentityKeychain.save(secretHex: preview.secretHex) {
            account = identity(forSecret: preview.secretHex)
        }
        self.preview = nil
        busy = false
    }

    func cancelPreview() {
        preview = nil
        importError = nil
    }

    /// Destructive: removes the stored secret after explicit confirmation.
    func removeAccount() {
        IdentityKeychain.clear()
        account = nil
        preview = nil
    }

    // MARK: - Signing (local key)

    /// Signs a 32-byte message hash with the stored local key. Returns nil
    /// when no local key exists or signing is refused.
    func signLocally(_ messageHex: String) async -> String? {
        guard let secret = IdentityKeychain.loadSecret() else { return nil }
        var aux = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, 32, &aux) == errSecSuccess else { return nil }
        let auxHex = aux.map { String(format: "%02x", $0) }.joined()
        return bridge.signDetached(messageHex: messageHex, secretHex: secret, auxHex: auxHex)
    }

    // MARK: - Helpers

    private func identity(forSecret secretHex: String) -> AccountIdentity? {
        guard let pubkeyHex = bridge.derivePublicKey(secretHex: secretHex),
              let npub = bridge.npubEncode(pubkeyHex: pubkeyHex) else { return nil }
        return AccountIdentity(pubkeyHex: pubkeyHex, npub: npub, signerKind: .localKey)
    }
}
