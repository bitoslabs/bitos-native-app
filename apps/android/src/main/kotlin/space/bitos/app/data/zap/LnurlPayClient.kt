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

    /**
     * Fetches /.well-known/lnurlp/<user> for a user@domain address (shared
     * `LnurlPay.payEndpointUrl`: domain lowercased, local part preserved —
     * APP-014 fix: the old whole-string lowercase broke case-sensitive
     * providers and silently built wrong URLs).
     */
    suspend fun fetchPayRequest(lud16: String): LnurlPay.PayRequest = withContext(Dispatchers.IO) {
        val url = LnurlPay.payEndpointUrl(lud16) ?: throw ZapFailure("invalid lightning address")
        val body = get(url) ?: throw ZapFailure("LNURL server unreachable")
        LnurlPay.parsePayRequest(body)
            ?: throw ZapFailure(LnurlPay.providerError(body) ?: "Lightning provider rejected the request.")
    }

    /**
     * Fetches the invoice. [nostrEventJson] is the BARE signed kind-9734
     * event object `{...}` (LUD-06: relay frames are rejected by servers —
     * APP-014 fix); null when the server disallows nostr zaps.
     */
    suspend fun fetchInvoice(
        payRequest: LnurlPay.PayRequest,
        amountMillisats: Long,
        nostrEventJson: String?,
        lud16: String,
    ): LnurlPay.Invoice = withContext(Dispatchers.IO) {
        val url = LnurlPay.buildCallbackUrl(payRequest, amountMillisats, nostrEventJson, lud16)
            ?: throw ZapFailure(LnurlPay.amountFailure(payRequest, amountMillisats) ?: "invalid zap request")
        val body = get(url) ?: throw ZapFailure("Could not create a Lightning invoice.")
        LnurlPay.parseInvoice(body)
            ?: throw ZapFailure(LnurlPay.providerError(body) ?: "No invoice was returned.")
    }

    /** LUD-21 settle classification (shared rule; host-side HTTP here). */
    suspend fun fetchVerifySettled(verifyUrl: String): Boolean = withContext(Dispatchers.IO) {
        val body = get(verifyUrl) ?: return@withContext false
        LnurlPay.verifySettled(body)
    }

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()?.takeIf { it.length <= 65_536 }
        }
    }.getOrNull()
}
