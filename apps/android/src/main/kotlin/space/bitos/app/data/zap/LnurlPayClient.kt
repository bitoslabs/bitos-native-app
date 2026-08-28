package space.bitos.app.data.zap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import space.bitos.core.model.LnurlPay
import java.util.concurrent.TimeUnit

/**
 * LNURL-pay HTTP client (SOC-008 client path): fetches the pay params for a
 * lud16 address, then the callback URL carrying the signed kind-9734 zap
 * request. Returns the bolt11 invoice for external-wallet payment; no key
 * or wallet connection is involved here.
 */
class LnurlPayClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {

    class ZapFailure(reason: String) : Exception(reason)

    /** Fetches /.well-known/lnurlp/<user> for a user@domain address. */
    suspend fun fetchPayRequest(lud16: String): LnurlPay.PayRequest = withContext(Dispatchers.IO) {
        val parts = lud16.trim().lowercase().split("@")
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) throw ZapFailure("invalid lightning address")
        val url = "https://" + parts[1] + "/.well-known/lnurlp/" + parts[0]
        val body = get(url) ?: throw ZapFailure("LNURL server unreachable")
        LnurlPay.parsePayRequest(body) ?: throw ZapFailure("invalid LNURL-pay response")
    }

    /**
     * Fetches the invoice. [nostrEventJson] is the signed kind-9734 request
     * (URL-encoded by the shared builder); null when the server disallows
     * nostr zaps.
     */
    suspend fun fetchInvoice(
        payRequest: LnurlPay.PayRequest,
        amountMillisats: Long,
        nostrEventJson: String?,
        lud16: String,
    ): String = withContext(Dispatchers.IO) {
        val url = LnurlPay.buildCallbackUrl(payRequest, amountMillisats, nostrEventJson, lud16)
            ?: throw ZapFailure("amount out of range")
        val body = get(url) ?: throw ZapFailure("LNURL server unreachable")
        LnurlPay.parseInvoice(body)?.paymentRequest ?: throw ZapFailure("no invoice returned")
    }

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()?.takeIf { it.length <= 65_536 }
        }
    }.getOrNull()
}
