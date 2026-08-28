package space.bitos.core.model

import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.publish.NoteComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZapTest {

    private val payer = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
    private val recipient = "e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301"
    private val targetId = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
    private val composer = NoteComposer(clock = { 1_710_000_000 })

    private val payJson = """
        {"callback":"https://pay.example/callback","minSendable":1000,"maxSendable":100000000,
         "tag":"payRequest","allowsNostr":true,"nostrPubkey":"${"ab".repeat(32)}","commentAllowed":256}
    """.trimIndent()

    @Test
    fun composesNip57ZapRequest() {
        val zap = composer.composeZapRequest(
            recipientPubkey = recipient,
            amountMillisats = 21_000,
            relays = listOf("wss://relay.damus.io", "wss://nos.lol", "http://bad"),
            lnurlHint = "user@wallet.example",
            comment = "  great work!  ",
            authorPubkey = payer,
            targetEventId = targetId,
        )!!
        assertEquals(9_734, zap.kind)
        assertEquals("great work!", zap.content)
        assertEquals(
            listOf(
                listOf("p", recipient),
                listOf("e", targetId),
                listOf("relays", "wss://relay.damus.io", "wss://nos.lol"),
                listOf("amount", "21000"),
                listOf("lnurl", "user@wallet.example"),
            ),
            zap.tags,
        )
        val expected = NostrEventCodec.computeId(
            Sha256EventHasher, payer, 1_710_000_000, 9_734, zap.tags, "great work!",
        )
        assertEquals(expected, zap.idHex)
    }

    @Test
    fun zapRequestRejectsInvalidInput() {
        assertNull(composer.composeZapRequest("zz", 1000, listOf("wss://r"), "a@b.c", "", payer))
        assertNull(composer.composeZapRequest(recipient, 0, listOf("wss://r"), "a@b.c", "", payer))
        assertNull(composer.composeZapRequest(recipient, 1000, listOf("http://no"), "a@b.c", "", payer))
        assertNull(composer.composeZapRequest(recipient, 1000, listOf("wss://r"), "", "", payer))
        assertNull(composer.composeZapRequest(recipient, 1000, listOf("wss://r"), "a@b.c", "", payer, targetEventId = "bad"))
    }

    @Test
    fun parsesLnurlPayRequest() {
        val pay = LnurlPay.parsePayRequest(payJson)!!
        assertEquals("https://pay.example/callback", pay.callback)
        assertEquals(1000, pay.minSendableMillisats)
        assertTrue(pay.allowsNostr)

        assertNull(LnurlPay.parsePayRequest("""{"tag":"withdrawRequest"}"""))
        assertNull(LnurlPay.parsePayRequest("not json"))
        // Non-HTTPS callbacks are rejected by construction.
        assertNull(LnurlPay.parsePayRequest(payJson.replace("https://", "http://")))
    }

    @Test
    fun buildsCallbackUrlAndParsesInvoice() {
        val pay = LnurlPay.parsePayRequest(payJson)!!
        val url = LnurlPay.buildCallbackUrl(pay, 21_000, """{"id":"x"}""", "user@wallet.example")!!
        assertTrue(url.startsWith("https://pay.example/callback?amount=21000"))
        assertTrue(url.contains("nostr=%7B%22id%22%3A%22x%22%7D"))

        // Out-of-range amounts are refused.
        assertNull(LnurlPay.buildCallbackUrl(pay, 1, null, "user@wallet.example"))
        assertNull(LnurlPay.buildCallbackUrl(pay, 200_000_000, null, "user@wallet.example"))

        val invoice = LnurlPay.parseInvoice("""{"pr":"lnbc210u1p3xq8z9abcdefghijklmnopqrstuvwxyz","status":"OK"}""")!!
        assertTrue(invoice.paymentRequest.startsWith("lnbc"))
        assertNull(LnurlPay.parseInvoice("""{"status":"ERROR","reason":"oops"}"""))
        assertNull(LnurlPay.parseInvoice("""{"pr":"not-an-invoice"}"""))
    }

    @Test
    fun zapReceiptTargetExtraction() {
        val receipt = NostrEvent(
            id = EventId.parse("55".repeat(32))!!,
            pubkey = Pubkey.parse("66".repeat(32))!!,
            createdAt = 1_000,
            kind = ZapReceipt.RECEIPT_KIND,
            tags = listOf(listOf("p", recipient), listOf("e", targetId)),
            content = "",
            signature = null,
            receivedFromRelay = null,
        )
        assertEquals(targetId, ZapReceipt.targetEventId(receipt))
        assertNull(ZapReceipt.targetEventId(receipt.copy(kind = 1)))
    }

    // ------------------------------------------------------------------
    // APP-012: bolt11 amount + verified 9734 sender recovery.
    // ------------------------------------------------------------------

    @Test
    fun bolt11AmountParsingFollowsTheHrpMultiplierTable() {
        val filler = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        assertEquals(2_000_000L, Bolt11.amountMillisats("lnbc20u1$filler"))
        assertEquals(100_000_000_000L, Bolt11.amountMillisats("lnbc11$filler")) // 1 whole BTC
        assertEquals(21_000L, Bolt11.amountMillisats("lnbc210n1$filler"))
        assertEquals(150L, Bolt11.amountMillisats("lnbc1500p1$filler"))
        // Amount-less, testnet, zero, oversized and malformed HRPs.
        assertNull(Bolt11.amountMillisats("lnbc1")) // below length bound
        assertNull(Bolt11.amountMillisats("lnbc$filler")) // amount-less
        assertNull(Bolt11.amountMillisats("lntb20u1$filler"))
        assertNull(Bolt11.amountMillisats("lnbc0u1$filler"))
        assertNull(Bolt11.amountMillisats("lnbc${"1".repeat(10)}u1$filler"))
        assertNull(Bolt11.amountMillisats("lnbc1.5u1$filler"))
        assertNull(Bolt11.amountMillisats("lnbc15p1$filler")) // 1.5 msat — not whole
    }

    @Test
    fun zapSenderRecoveryVerifiesTheEmbeddedRequest() = kotlinx.coroutines.runBlocking {
        val signer = space.bitos.core.identity.DeterministicTestSigner("05".repeat(32))
        val payerKey = signer.publicKeyHex()
        val unsigned = composer.composeZapRequest(
            recipientPubkey = recipient,
            amountMillisats = 21_000,
            relays = listOf("wss://relay.damus.io"),
            lnurlHint = "user@wallet.example",
            comment = "nice",
            authorPubkey = payerKey,
            targetEventId = targetId,
        )!!
        val signature = signer.sign(unsigned.messageBytes())!!
        val requestJson = composer.publishMessage(unsigned, signature)!!.let {
            it.substringAfter(',').removeSuffix("]")
        }

        fun receipt(description: String?, bolt11: String = "lnbc210n1p3xq8z9abcdefghijklmnop") = NostrEvent(
            id = EventId.parse("55".repeat(32))!!,
            pubkey = Pubkey.parse("66".repeat(32))!!,
            createdAt = 1_000,
            kind = ZapReceipt.RECEIPT_KIND,
            tags = buildList {
                add(listOf("p", recipient))
                add(listOf("e", targetId))
                add(listOf("bolt11", bolt11))
                description?.let { add(listOf("description", it)) }
            },
            content = "",
            signature = null,
            receivedFromRelay = null,
        )

        val valid = receipt(requestJson)
        assertEquals(payerKey, ZapReceipt.senderPubkey(valid, recipient))
        assertEquals(21_000L, ZapReceipt.amountMillisats(valid))

        // Tampered/unsigned/mistargeted descriptions attribute nothing.
        assertNull(ZapReceipt.senderPubkey(receipt(requestJson.replace("nice", "fake")), recipient))
        assertNull(ZapReceipt.senderPubkey(receipt("not json"), recipient))
        assertNull(ZapReceipt.senderPubkey(receipt(null), recipient))
        // A description whose p tag targets someone else is not this receipt's payer.
        val otherRecipient = composer.composeZapRequest(
            recipientPubkey = payerKey,
            amountMillisats = 21_000,
            relays = listOf("wss://relay.damus.io"),
            lnurlHint = "user@wallet.example",
            comment = "nice",
            authorPubkey = payerKey,
        )!!
        val otherSignature = signer.sign(otherRecipient.messageBytes())!!
        val otherJson = composer.publishMessage(otherRecipient, otherSignature)!!.let {
            it.substringAfter(',').removeSuffix("]")
        }
        assertNull(ZapReceipt.senderPubkey(receipt(otherJson), recipient))
    }
}
