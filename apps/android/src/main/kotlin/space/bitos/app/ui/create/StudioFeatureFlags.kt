package space.bitos.app.ui.create

import space.bitos.app.BuildConfig

/**
 * Studio Create-hub feature flags (plan
 * `docs/native/create-hub-sound-remix-plan.md` §Documentation and
 * rollout): `studio_sound_source_picker` / `studio_remix_source_picker`
 * ship dark outside internal builds. A `false` flag hides the
 * corresponding NEW hub entry only — it never removes drafts, the
 * Bitz-card sound/remix actions, local sound-library assets or direct
 * relay access.
 */
object StudioFeatureFlags {
    /** MEM-003b/c: "Start with a sound" hub entry + sound source picker. */
    val soundSourcePickerEnabled: Boolean = BuildConfig.STUDIO_SOUND_SOURCE_PICKER_ENABLED

    /** MEM-005a/b: "Remix a Bitz" hub entry + remix source picker. */
    val remixSourcePickerEnabled: Boolean = BuildConfig.STUDIO_REMIX_SOURCE_PICKER_ENABLED
}
