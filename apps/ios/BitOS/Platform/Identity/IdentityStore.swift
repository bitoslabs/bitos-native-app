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

/** One saved account row (public projection; secrets stay in Keychain slots). */
struct RegisteredAccountRow: Identifiable, Equatable {
    let pubkeyHex: String
    let npub: String
    var displayName: String?
    var id: String { pubkeyHex }
}

@MainActor
@Observable
final class IdentityStore {
    private(set) var account: AccountIdentity?
    var preview: IdentityPreview?
    private(set) var importError: String?
    private(set) var busy = false

    /** Multi-account registry (APP-018a row 1) — public projections only. */
    private(set) var registeredAccounts: [RegisteredAccountRow] = []
    private(set) var activeRegistryPubkey: String?

    private let bridge: BusinessCoreBridge
    private static let registryKey = "account_registry_v1"
    private static let activeKey = "active_pubkey"
    private var registryDefaults: UserDefaults

    init(bridge: BusinessCoreBridge = BusinessCoreBridge(),
         registryDefaults: UserDefaults? = UserDefaults(suiteName: "bitos.accounts")) {
        self.bridge = bridge
        self.registryDefaults = registryDefaults ?? .standard
        loadRegistry()
        let activeSecret = activeRegistryPubkey.flatMap { IdentityKeychain.loadSecret(slotPubkey: $0) }
            ?? IdentityKeychain.loadSecret()
        if let secret = activeSecret {
            account = identity(forSecret: secret)
        }
    }

    private func loadRegistry() {
        let wire = registryDefaults.string(forKey: Self.registryKey) ?? ""
        registeredAccounts = bridge.accountRegistryDecode(json: wire).map {
            RegisteredAccountRow(pubkeyHex: $0.pubkeyHex, npub: $0.npub, displayName: $0.displayName)
        }
        activeRegistryPubkey = registryDefaults.string(forKey: Self.activeKey)
    }

    private func persistRegistry() {
        let wire = bridge.accountRegistryEncode(
            accounts: registeredAccounts.map {
                BusinessCoreBridge.RegisteredAccountWire(
                    pubkeyHex: $0.pubkeyHex, npub: $0.npub,
                    displayName: $0.displayName, addedAtSeconds: 0
                )
            }
        )
        registryDefaults.set(wire, forKey: Self.registryKey)
    }

    private func setActive(_ pubkeyHex: String?) {
        if let pubkeyHex, !registeredAccounts.contains(where: { $0.pubkeyHex == pubkeyHex }) { return }
        activeRegistryPubkey = pubkeyHex
        registryDefaults.set(pubkeyHex, forKey: Self.activeKey)
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
        if IdentityKeychain.save(secretHex: preview.secretHex),
           let identity = identity(forSecret: preview.secretHex) {
            account = identity
            IdentityKeychain.save(secretHex: preview.secretHex, slotPubkey: identity.pubkeyHex)
            registeredAccounts.removeAll { $0.pubkeyHex == identity.pubkeyHex }
            registeredAccounts.append(
                RegisteredAccountRow(pubkeyHex: identity.pubkeyHex, npub: identity.npub, displayName: nil)
            )
            persistRegistry()
            setActive(identity.pubkeyHex)
        }
        self.preview = nil
        busy = false
    }

    func cancelPreview() {
        preview = nil
        importError = nil
    }

    /// Sign-out = deactivate (legacy parity): the pointer clears, slots survive.
    func signOut() {
        setActive(nil)
        account = nil
        preview = nil
    }

    /// Destructive: removes the ACTIVE secret + legacy slot after explicit
    /// confirmation (security danger zone).
    func removeAccount() {
        if let pubkey = account?.pubkeyHex {
            IdentityKeychain.removeSlot(pubkey: pubkey)
            registeredAccounts.removeAll { $0.pubkeyHex == pubkey }
            persistRegistry()
        }
        IdentityKeychain.clear()
        setActive(nil)
        account = nil
        preview = nil
    }

    /// One-tap account switch (registry row → sealed slot → active).
    func switchTo(pubkeyHex: String) {
        guard account?.pubkeyHex != pubkeyHex else { return }
        busy = true
        defer { busy = false }
        guard let secret = IdentityKeychain.loadSecret(slotPubkey: pubkeyHex),
              let identity = identity(forSecret: secret) else {
            // Slot lost (keychain wipe): drop the dead row.
            registeredAccounts.removeAll { $0.pubkeyHex == pubkeyHex }
            persistRegistry()
            return
        }
        setActive(pubkeyHex)
        account = identity
        preview = nil
    }

    /// Destructive per-account removal: wipes the sealed slot + registry row.
    func removeRegisteredAccount(pubkeyHex: String) {
        IdentityKeychain.removeSlot(pubkey: pubkeyHex)
        registeredAccounts.removeAll { $0.pubkeyHex == pubkeyHex }
        persistRegistry()
        if account?.pubkeyHex == pubkeyHex {
            setActive(nil)
            account = nil
        }
    }

    // MARK: - Signing (local key)

    /// Signs a 32-byte message hash with the active account's local key.
    func signLocally(_ messageHex: String) async -> String? {
        let secret = activeRegistryPubkey.flatMap { IdentityKeychain.loadSecret(slotPubkey: $0) }
            ?? IdentityKeychain.loadSecret()
        guard let secret else { return nil }
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
