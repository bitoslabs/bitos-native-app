import Foundation

/// Identity feature flags (plan §Infrastructure): NIP-46 ships dark behind
/// an internal-build kill-switch. `false` blocks NEW setup and NEW
/// requests; it never deletes saved accounts, drafts, identity export or
/// relay access. Flip per internal build; removal conditions live in
/// docs/native/identity-nip46-nip55-plan.md. (NIP-55 is Android-only.)
enum IdentityFlags {
    static let nip46Enabled: Bool = false
}
