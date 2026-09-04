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
/// `pubkeyHex` is derived once at preview creation so confirmation needs
/// no second secp256k1 pass.
struct IdentityPreview: Sendable, Equatable {
    let pubkeyHex: String
    let npub: String
    let secretHex: String
    let replacesExisting: Bool
    /// True when the key was generated on this device — the confirm gate
    /// offers the one-time backup reveal only then.
    let isNewKey: Bool
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

    /// Session cache of the active account's secret — memory only, never
    /// logged or persisted (audit R10). Repopulated by restore/confirm/switch;
    /// cleared by every account mutation.
    private var cachedActiveSecret: String?
    /// Coalesces concurrent restore calls and lets a restore detect that the
    /// user changed accounts mid-hop (its result is then discarded).
    private var restoreTask: Task<Void, Never>?
    private var sessionEpoch = 0

    init(bridge: BusinessCoreBridge = BusinessCoreBridge(),
         registryDefaults: UserDefaults? = UserDefaults(suiteName: "bitos.accounts")) {
        self.bridge = bridge
        self.registryDefaults = registryDefaults ?? .standard
        loadRegistry()
        // Cold start is public-registry only (audit R10): zero Keychain reads,
        // zero crypto. The active row becomes a provisional account; the
        // shell's restore task re-derives off-main and replaces or clears it.
        // Registry rows already persist npub, so no derive is needed to show
        // the signed-in shell.
        account = activeRegistryPubkey.flatMap { active in
            registeredAccounts.first { $0.pubkeyHex == active }
        }.map {
            AccountIdentity(pubkeyHex: $0.pubkeyHex, npub: $0.npub, signerKind: .localKey)
        }
    }

    /// Verifies the active registry entry against its sealed slot: loads the
    /// secret (active slot first, legacy fallback), derives the pubkey
    /// off-main, and replaces the provisional account — or drops a dead
    /// entry. Concurrent callers join the in-flight restore.
    func restoreActiveSession() async {
        if let restoreTask {
            await restoreTask.value
            return
        }
        let epoch = sessionEpoch
        let task = Task { [weak self] in
            guard let self else { return }
            await self.runRestore(epoch: epoch)
        }
        restoreTask = task
        await task.value
        restoreTask = nil
    }

    private func runRestore(epoch: Int) async {
        // A non-empty registry with no active pointer means signed-out
        // (sign-out deliberately preserves slots): restore must not
        // resurrect. The accept-and-backfill path below only serves fresh
        // pre-registry installs.
        let legacyInstall = registeredAccounts.isEmpty
        // Verify path (provisional account) and legacy path (no registry row
        // yet) both load the secret the same way: active slot first, then the
        // legacy single-secret slot.
        let secret = activeRegistryPubkey.flatMap { IdentityKeychain.loadSecret(slotPubkey: $0) }
            ?? IdentityKeychain.loadSecret()
        guard let secret else {
            // No sealed secret anywhere (fresh install or keychain wipe):
            // drop any provisional account rather than risk a wrong key.
            account = nil
            cachedActiveSecret = nil
            return
        }
        let derived = await Self.deriveIdentity(secretHex: secret)
        guard epoch == sessionEpoch else { return }
        let provisional = account?.pubkeyHex
        guard let derived,
              provisional != nil ? derived.pubkeyHex == provisional : legacyInstall else {
            account = nil
            cachedActiveSecret = nil
            return
        }
        account = derived
        cachedActiveSecret = secret
        // Legacy migration: an account created before the registry ships
        // backfills its row so the switcher shows it (and Add works).
        if !registeredAccounts.contains(where: { $0.pubkeyHex == derived.pubkeyHex }) {
            _ = IdentityKeychain.save(secretHex: secret, slotPubkey: derived.pubkeyHex)
            registeredAccounts.append(
                RegisteredAccountRow(pubkeyHex: derived.pubkeyHex, npub: derived.npub, displayName: nil)
            )
            persistRegistry()
            if activeRegistryPubkey == nil { setActive(derived.pubkeyHex) }
        }
    }

    /// Off-main derivation. `nonisolated static` runs on the global
    /// concurrent executor (SE-0338); the KMP bridge is not Sendable so it
    /// is constructed inside, never captured across isolation.
    private nonisolated static func deriveIdentity(secretHex: String) async -> AccountIdentity? {
        let bridge = BusinessCoreBridge()
        guard let pubkeyHex = bridge.derivePublicKey(secretHex: secretHex),
              let npub = bridge.npubEncode(pubkeyHex: pubkeyHex) else { return nil }
        return AccountIdentity(pubkeyHex: pubkeyHex, npub: npub, signerKind: .localKey)
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
        busy = true
        let replacesExisting = account != nil
        Task {
            var bytes = [UInt8](repeating: 0, count: 32)
            guard SecRandomCopyBytes(kSecRandomDefault, 32, &bytes) == errSecSuccess else {
                busy = false
                return
            }
            let secretHex = bytes.map { String(format: "%02x", $0) }.joined()
            guard let identity = await Self.deriveIdentity(secretHex: secretHex) else {
                busy = false
                return
            }
            preview = IdentityPreview(
                pubkeyHex: identity.pubkeyHex,
                npub: identity.npub,
                secretHex: secretHex,
                replacesExisting: replacesExisting,
                isNewKey: true
            )
            importError = nil
            busy = false
        }
    }

    /// Validates an nsec/hex import (shared `KeyImportForm` rule) and
    /// prepares it for confirmation.
    func importKeyPreview(_ input: String) {
        let wire = bridge.keyImportCheck(raw: input)
        guard wire.verdict == "READY", let secret = wire.secretHex else {
            importError = wire.message
            return
        }
        busy = true
        let replacesExisting = account != nil
        Task {
            guard let identity = await Self.deriveIdentity(secretHex: secret) else {
                importError = "Key rejected by the signer."
                busy = false
                return
            }
            preview = IdentityPreview(
                pubkeyHex: identity.pubkeyHex,
                npub: identity.npub,
                secretHex: secret,
                replacesExisting: replacesExisting,
                isNewKey: false
            )
            importError = nil
            busy = false
        }
    }

    /// Clears a stale submit error while the user edits the field.
    func clearImportError() {
        importError = nil
    }

    /// nsec encoding of the pending preview secret. Only the confirm-gate
    /// backup reveal may display it; never logged or persisted.
    var previewSecretNsec: String? {
        preview.flatMap { bridge.nsecEncode(secretHex: $0.secretHex) }
    }

    /// Stores the previewed identity. The preview already carries the derived
    /// pubkey/npub, so confirming needs no second secp256k1 pass.
    func confirmPreview() {
        guard let preview else { return }
        busy = true
        let identity = AccountIdentity(
           pubkeyHex: preview.pubkeyHex, npub: preview.npub, signerKind: .localKey
        )
        if IdentityKeychain.save(secretHex: preview.secretHex),
           IdentityKeychain.save(secretHex: preview.secretHex, slotPubkey: preview.pubkeyHex) {
           account = identity
           cachedActiveSecret = preview.secretHex
           sessionEpoch += 1
           registeredAccounts.removeAll { $0.pubkeyHex == preview.pubkeyHex }
           registeredAccounts.append(
               RegisteredAccountRow(pubkeyHex: preview.pubkeyHex, npub: preview.npub, displayName: nil)
           )
           persistRegistry()
           setActive(preview.pubkeyHex)
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
        cachedActiveSecret = nil
        sessionEpoch += 1
    }

    /// Destructive: removes the ACTIVE secret + legacy slot after explicit
    /// confirmation (security danger zone). Removing the active account hops
    /// to the next sealed account instead of dropping to browse — the
    /// signed-out shell has no switcher, so without the hop the remaining
    /// accounts would be unreachable.
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
        cachedActiveSecret = nil
        sessionEpoch += 1
        if let next = registeredAccounts.first {
            switchTo(pubkeyHex: next.pubkeyHex)
        }
    }

    /// One-tap account switch (registry row → sealed slot → active). The
    /// derive runs off-main; a concurrent account change invalidates the hop.
    func switchTo(pubkeyHex: String) {
        guard account?.pubkeyHex != pubkeyHex else { return }
        busy = true
        sessionEpoch += 1
        let epoch = sessionEpoch
        Task {
           // Keychain read on the actor, derive off it (same split as restore).
           let secret = IdentityKeychain.loadSecret(slotPubkey: pubkeyHex)
           let derived = secret == nil ? nil : await Self.deriveIdentity(secretHex: secret!)
           guard epoch == sessionEpoch else {
               busy = false
               return
           }
           guard let secret, let derived else {
               // Slot lost (keychain wipe): drop the dead row.
               registeredAccounts.removeAll { $0.pubkeyHex == pubkeyHex }
               persistRegistry()
               busy = false
               return
           }
           setActive(pubkeyHex)
           account = derived
           preview = nil
           cachedActiveSecret = secret
           busy = false
        }
    }

    /// Destructive per-account removal: wipes the sealed slot + registry row.
    /// Removing the ACTIVE account hops to the next sealed account instead of
    /// dropping to browse (same rationale as `removeAccount`).
    func removeRegisteredAccount(pubkeyHex: String) {
        IdentityKeychain.removeSlot(pubkey: pubkeyHex)
        registeredAccounts.removeAll { $0.pubkeyHex == pubkeyHex }
        persistRegistry()
        if account?.pubkeyHex == pubkeyHex {
           setActive(nil)
           account = nil
           cachedActiveSecret = nil
           sessionEpoch += 1
           if let next = registeredAccounts.first {
               switchTo(pubkeyHex: next.pubkeyHex)
           }
        }
    }

    // MARK: - Signing (local key)

    /// Signs a 32-byte message hash with the active account's local key.
    /// Session cache first (per-sign Keychain read eliminated, audit R10);
    /// Keychain fallback resolves the ACTIVE slot only — never the legacy
    /// slot, which after a switch may hold a different account's key.
    /// The scalar math runs off-main (~100ms is too slow for UI).
    func signLocally(_ messageHex: String) async -> String? {
        guard account != nil else { return nil }
        let secret = cachedActiveSecret
           ?? activeRegistryPubkey.flatMap { IdentityKeychain.loadSecret(slotPubkey: $0) }
        guard let secret else { return nil }
        if cachedActiveSecret == nil { cachedActiveSecret = secret }
        return await Self.signDetached(messageHex: messageHex, secretHex: secret)
    }

    // MARK: - Helpers

    /// Off-main signing, same executor rule as `deriveIdentity`: fresh bridge
    /// inside the nonisolated helper, inputs are plain Sendable values.
    private nonisolated static func signDetached(messageHex: String, secretHex: String) async -> String? {
        var aux = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, 32, &aux) == errSecSuccess else { return nil }
        let auxHex = aux.map { String(format: "%02x", $0) }.joined()
        return BusinessCoreBridge().signDetached(messageHex: messageHex, secretHex: secretHex, auxHex: auxHex)
    }
}
