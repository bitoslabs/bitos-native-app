package space.bitos.core.feed

/**
 * Advisory zap permission for feed notes (BitOS-namespaced like the
 * `bitz:edge` remix form, advisory like the `license` tag): a publisher
 * that turns "Zap settings" off stamps `["bitz:zaps", "off"]` and our
 * cards hide the zap action on that note. Relays and other clients stay
 * free to ignore the tag — the open network is advisory-only, same as
 * remix rights (§17.3). This is NOT pay-per-view: zap-gated unlocking
 * needs invoice + preimage verification and stays future work.
 */
object ZapPolicy {

    /** Tag name for the advisory off marker. */
    const val OFF_TAG = "bitz:zaps"

    /** The tag a publish stamps when the author turns zaps off. */
    fun offTag(): List<String> = listOf(OFF_TAG, "off")

    /** True when the tags carry the advisory off marker. */
    fun isDisabled(tags: List<List<String>>): Boolean =
        tags.any { it.size >= 2 && it[0] == OFF_TAG && it[1] == "off" }
}
