package space.bitos.app.data.zap

import android.content.Context
import space.bitos.core.model.SentZapLedger
import space.bitos.core.model.SentZapRecord

/**
 * APP-014 sent-zap ledger adapter: the versioned shared wire
 * (`SentZapLedger`) in SharedPreferences. Sent zaps are local-only records
 * (the 9735 is published by the recipient's provider, never observable as
 * `author = me`).
 */
class SentZapsStore(context: Context) {
    private val prefs = context.getSharedPreferences("bitos_sent_zaps", Context.MODE_PRIVATE)

    fun load(): List<SentZapRecord> =
        prefs.getString(KEY_WIRE, null)?.let(SentZapLedger::decode) ?: emptyList()

    fun record(record: SentZapRecord) {
        val next = SentZapLedger.withRecord(load(), record)
        prefs.edit().putString(KEY_WIRE, SentZapLedger.encode(next)).apply()
    }

    private companion object {
        const val KEY_WIRE = "wire"
    }
}
