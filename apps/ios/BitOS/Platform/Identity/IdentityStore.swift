import BusinessCore
import Foundation
import Observation
import Security

/// Signer kind mirror (shared `SignerKind`).
enum PlatformSignerKind: Sendable, Equatable {
    case localKey
    case nip46
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
    /// Shared `SignerKind` name (LOCAL_KEY / REMOTE_NIP46 / EXTERNAL_NIP55 / READ_ONLY).
    var signerKind: String
    /// NIP-46 metadata JSON or nil (bunker pubkey, relays, label).
    var nip46Json: String?
    /// NIP-55 metadata JSON or nil (pinned package).
    var nip55Json: String?
    var id: String { pubkeyHex }

    init(pubkeyHex: String, npub: String, displayName: String?,
         signerKind: String = "LOCAL_KEY", nip46Json: String? = nil, nip55Json: String? = nil) {
        self.pubkeyHex = pubkeyHex
        self.npub = npub
        self.displayName = displayName
        self.signerKind = signerKind
        self.nip46Json = nip46Json
        self.nip55Json = nip55Json
    }
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
            RegisteredAccountRow(
                pubkeyHex: $0.pubkeyHex, npub: $0.npub, displayName: $0.displayName,
                signerKind: $0.signerKind, nip46Json: $0.nip46Json, nip55Json: $0.nip55Json
            )
        }
        activeRegistryPubkey = registryDefaults.string(forKey: Self.activeKey)
    }

    private func persistRegistry() {
        let wire = bridge.accountRegistryEncode(
            accounts: registeredAccounts.map {
                BusinessCoreBridge.RegisteredAccountWire(
                    pubkeyHex: $0.pubkeyHex, npub: $0.npub,
                    displayName: $0.displayName, addedAtSeconds: 0,
                    signerKind: $0.signerKind, nip46Json: $0.nip46Json, nip55Json: $0.nip55Json
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


    /// ID-006d: switch, sign-out, removal and app exit land here — cancel
    /// in-flight waits, close subscriptions, tear sockets down; removal
    /// additionally wipes the sealed NIP-46 transport key.
    private func shutdownRemoteSigners(wipeTransportSecret: Bool = false, for pubkey: String? = nil) {
        let session = activeNip46Session
        activeNip46Session = nil
        nip46SessionAccount = nil
        Task {
            await session?.shutdown()
            if wipeTransportSecret {
                await session?.wipeTransportSecret()
                if let pubkey { IdentityKeychain.removeNip46TransportSlot(pubkey: pubkey) }
            }
        }
    }

    /// Sign-out = deactivate (legacy parity): the pointer clears, slots survive.
    func signOut() {
        shutdownRemoteSigners()
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
            shutdownRemoteSigners(wipeTransportSecret: true, for: pubkey)
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
        shutdownRemoteSigners()
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
        shutdownRemoteSigners(wipeTransportSecret: true, for: pubkeyHex)
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

    // MARK: - Publishing signer seam (ID-006c)

    /// Active NIP-46 session handle for the awaiting-signer UI and ID-006d cleanup.
    private(set) var activeNip46Session: Nip46SignerSession?

    /// The active account's registry row.
    private func activeRow() -> RegisteredAccountRow? {
        activeRegistryPubkey.flatMap { active in registeredAccounts.first { $0.pubkeyHex == active } }
    }

    /**
     * One signing seam for every publish site: local accounts sign the
     * message hash; NIP-46 accounts route the SAME canonical event
     * (supplied as the placeholder-signed frame the site just composed)
     * through the remote signer's validation gate. Returns the signature
     * hex or nil (refusal/timeout — the caller must not send any frame).
     */
    func signForPublishing(messageHex: String, unsignedFrame: String?) async -> String? {
        guard let row = activeRow() else { return nil }
        guard row.signerKind == "REMOTE_NIP46", IdentityFlags.nip46Enabled else {
            return await signLocally(messageHex)
        }
        guard let frame = unsignedFrame,
              let wire = BusinessCoreBridge().unsignedWireFromEventJson(eventJson: frame) else { return nil }
        let session = nip46Session(for: row)
        activeNip46Session = session
        let outcome = await session.approveEvent(
            requestId: "ios-" + messageHex.prefix(16),
            accountPubkeyHex: row.pubkeyHex,
            createdAtSeconds: Int64(wire["createdAtSeconds"] ?? "") ?? 0,
            kind: Int(wire["kind"] ?? "") ?? 0,
            tagsJson: wire["tagsJson"] ?? "[]",
            content: wire["content"] ?? ""
        )
        if case .signed(_, _, let sig) = outcome { return sig }
        return nil
    }

    /// Lazily builds the NIP-46 session for a registry row (rebuilt when
    /// the active account changes — ID-006d invalidation).
    func nip46Session(for row: RegisteredAccountRow) -> Nip46SignerSession {
        if let existing = activeNip46Session, nip46SessionAccount == row.pubkeyHex {
            return existing
        }
        let session = Nip46SignerSession(config: Nip46SessionConfig(
            bunkerPubkeyHex: Self.decodeBunkerPubkey(from: row.nip46Json) ?? row.pubkeyHex,
            relays: Self.decodeRelays(from: row.nip46Json),
            label: nil,
            accountPubkeyHex: row.pubkeyHex
        ))
        nip46SessionAccount = row.pubkeyHex
        return session
    }

    private var nip46SessionAccount: String?

    private static func decodeBunkerPubkey(from json: String?) -> String? {
        guard let json, let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return dict["bunkerPubkeyHex"] as? String
    }

    private static func decodeRelays(from json: String?) -> [RelayURL] {
        guard let json, let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = dict["relays"] as? [String] else { return [] }
        return raw.compactMap(RelayURL.init(rawValue:))
    }

    // MARK: - Remote signer setup (ID-006b)

    struct RemoteSetupPreview: Equatable {
        let pubkeyHex: String
        let npub: String
        let bunkerPubkeyHex: String
        let relays: [String]
    }

    var remoteSetup: RemoteSetupPreview?
    var remoteSetupError: String?
    var remoteSetupBusy = false

    /// bunker:// setup: connect through an EPHEMERAL transport key and
    /// require the bunker's own `get_public_key` answer. Nothing is saved
    /// until the user confirms the returned npub.
    func startNip46Setup(uri: String) async {
        guard IdentityFlags.nip46Enabled else { return }
        remoteSetupError = nil
        remoteSetupBusy = true
        defer { remoteSetupBusy = false }
        guard let json = BusinessCoreBridge().nip46ParseUri(uri: uri),
              let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let bunker = dict["pubkey"] as? String,
              let relays = (dict["relays"] as? [String])?.compactMap(RelayURL.init(rawValue:)),
              !relays.isEmpty else {
            remoteSetupError = "That bunker link doesn't look valid."
            return
        }
        let pairingSecret = dict["secret"] as? String
        let session = Nip46SignerSession(
            config: Nip46SessionConfig(
                bunkerPubkeyHex: bunker, relays: relays, label: nil, accountPubkeyHex: ""
            ),
            ephemeralTransportKey: true
        )
        switch await session.connectAndGetPublicKey(pairingSecret: pairingSecret) {
        case .connected(let pubkey):
            guard let npub = BusinessCoreBridge().npubEncode(pubkeyHex: pubkey) else {
                remoteSetupError = "The signer returned an unusable key."
                return
            }
            remoteSetup = RemoteSetupPreview(
                pubkeyHex: pubkey, npub: npub,
                bunkerPubkeyHex: bunker, relays: relays.map { $0.rawValue }
            )
        case .failed:
            remoteSetupError = "The signer couldn't complete the connection. Try again when it's open."
        }
    }

    /// Saves the confirmed remote account (public metadata only).
    func confirmRemoteSetup() {
        guard let preview = remoteSetup else { return }
        registeredAccounts.removeAll { $0.pubkeyHex == preview.pubkeyHex }
        let metadataJson = """
        {"bunkerPubkeyHex":"\(preview.bunkerPubkeyHex)","relays":[\(preview.relays.map { "\"\($0)\"" }.joined(separator: ","))],"connectionVersion":1}
        """
        registeredAccounts.append(RegisteredAccountRow(
            pubkeyHex: preview.pubkeyHex, npub: preview.npub, displayName: nil,
            signerKind: "REMOTE_NIP46", nip46Json: metadataJson, nip55Json: nil
        ))
        persistRegistry()
        setActive(preview.pubkeyHex)
        account = AccountIdentity(pubkeyHex: preview.pubkeyHex, npub: preview.npub, signerKind: .nip46)
        cachedActiveSecret = nil
        sessionEpoch += 1
        remoteSetup = nil
        remoteSetupError = nil
    }

    func cancelRemoteSetup() {
        remoteSetup = nil
        remoteSetupError = nil
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
