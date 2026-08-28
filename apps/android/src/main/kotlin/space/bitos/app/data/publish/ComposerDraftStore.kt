package space.bitos.app.data.publish

import android.content.Context
import space.bitos.core.publish.ComposerDraft
import space.bitos.core.publish.ComposerDraftContract

/**
 * APP-008 draft persistence adapter: the versioned wire (shared
 * `ComposerDraftContract`) in SharedPreferences. One slot — the active
 * draft; saving an empty draft clears the slot.
 */
class ComposerDraftStore(context: Context) {
    private val prefs = context.getSharedPreferences("bitos_composer_draft", Context.MODE_PRIVATE)

    fun load(): ComposerDraft? =
        prefs.getString(KEY_WIRE, null)?.let(ComposerDraftContract::decode)

    fun save(draft: ComposerDraft) {
        if (draft.isEmpty) clear() else prefs.edit().putString(KEY_WIRE, ComposerDraftContract.encode(draft)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_WIRE).apply()
    }

    private companion object {
        const val KEY_WIRE = "wire"
    }
}
