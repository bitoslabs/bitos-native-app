package space.bitos.app.identity

import space.bitos.app.BuildConfig

/**
 * Identity feature flags (plan §Infrastructure): NIP-46/NIP-55 ship dark
 * behind internal-build kill-switches. `false` blocks NEW setup and NEW
 * requests; it never deletes saved accounts, drafts, identity export or
 * relay access. Flip per internal build config; removal conditions live
 * in docs/native/identity-nip46-nip55-plan.md.
 */
object IdentityFeatureFlags {
    /** NIP-46 remote signer support (setup + requests). */
    const val nip46Enabled: Boolean = BuildConfig.IDENTITY_NIP46_ENABLED

    /** NIP-55 Android system signer support (setup + requests). */
    const val nip55Enabled: Boolean = BuildConfig.IDENTITY_NIP55_ENABLED
}
