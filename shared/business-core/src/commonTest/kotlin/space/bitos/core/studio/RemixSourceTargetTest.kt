package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import space.bitos.core.feed.FeedNote
import space.bitos.core.model.EventId
import space.bitos.core.model.NostrEvent
import space.bitos.core.model.Pubkey

/**
 * MEM-003a battery for [RemixSourceTargets] — the cases are pinned verbatim
 * in `contracts/meme/remix-source-targets.json` (repo rule: protocol-facing
 * parsers carry fixtures). The shared event id is 0xaa×31 + 0x11.
 */
class RemixSourceTargetTest {

    private val eventId = "aa".repeat(31) + "11"
    private val note1 = "note14242424242424242424242424242424242424242424242424ggszd9z6v"
    private val nevent1 =
        "nevent1qqs24242424242424242424242424242424242424242424242425ygpyzamhwamhwamhwamhwamhwamhwamhwamhwamhwamhwamhwamhwajyqs4waehxw309aex2mrp0yhxy6t5daejuctswqppgamnwvaz7tmjv4kxz7fwv3sk6atn9e5k7wwqnrv"

    @Test
    fun nostrNoteEntityParsesToTheEventId() {
        val target = RemixSourceTargets.parse("nostr:$note1") as RemixTarget.Event
        assertEquals(eventId, target.id)
        assertTrue(target.relayHints.isEmpty())
    }

    @Test
    fun bareEntityWithWhitespaceParses() {
        val target = RemixSourceTargets.parse("  $note1 ") as RemixTarget.Event
        assertEquals(eventId, target.id)
    }

    @Test
    fun neventCarriesBoundedRelayHints() {
        val target = RemixSourceTargets.parse("nostr:$nevent1") as RemixTarget.Event
        assertEquals(eventId, target.id)
        assertEquals(
            listOf("wss://relay.bitos.app", "wss://relay.damus.io").take(RemixSourceTargets.MAX_RELAY_HINTS),
            target.relayHints,
        )
    }

    @Test
    fun bitosWebShareLinkResolvesNeventCarrier() {
        val target = RemixSourceTargets.parse("https://bitos.app/note/$nevent1") as RemixTarget.Event
        assertEquals(eventId, target.id)
        assertEquals(2, target.relayHints.size)
    }

    @Test
    fun bitosWebShareLinkResolvesHexCarrier() {
        val target = RemixSourceTargets.parse("https://bitos.app/note/$eventId") as RemixTarget.Event
        assertEquals(eventId, target.id)
    }

    @Test
    fun viewerLinksResolve() {
        for (url in listOf(
            "https://snort.social/e/$eventId",
            "https://snort.social/e/$note1?via=trending",
            "https://primal.net/e/$nevent1",
            "https://iris.to/$note1",
            "https://nostrapp.link/#$nevent1",
            "https://nostrapp.link/#nostr:$note1",
        )) {
            val target = RemixSourceTargets.parse(url) as RemixTarget.Event
            assertEquals(eventId, target.id, "failed for $url")
        }
    }

    @Test
    fun rejectionsAreNamed() {
        // (input, expected token) — mirrors contracts/meme/remix-source-targets.json.
        val cases = listOf(
            "   " to RemixTargetRejection.EMPTY,
            "" to RemixTargetRejection.EMPTY,
            ("nostr:" + "n".repeat(RemixSourceTargets.MAX_INPUT_LENGTH)) to RemixTargetRejection.OVERSIZED,
            "http://snort.social/e/$eventId" to RemixTargetRejection.NOT_HTTPS,
            "https://evil.example/e/$note1" to RemixTargetRejection.UNSUPPORTED_HOST,
            "https://snort.social/p/$note1" to RemixTargetRejection.UNSUPPORTED_HOST,
            "nostr:npub1sn0wdenkukak0d9dfczzeacvhkrgz92ak56egt7vdgzn8pv2wfqqhrjdv9" to
                RemixTargetRejection.PROFILE_TARGET,
            "nostr:naddr1qqxzurlz9wfhx2vgu3tw9hqmg6c9yhxc7ljdttn9uaz6jhx2u0pn5mlnkamhtkv6rxc6r38yd9n2" to
                RemixTargetRejection.ADDRESS_TARGET,
            "nostr:${note1.dropLast(1)}q" to RemixTargetRejection.MALFORMED_ENTITY,
            "https://bitos.app/note/${note1.dropLast(1)}q" to RemixTargetRejection.MALFORMED_ENTITY,
            eventId to RemixTargetRejection.NOT_AN_EVENT,
            "check out this bitz lol" to RemixTargetRejection.NOT_AN_EVENT,
        )
        for ((input, expected) in cases) {
            assertEquals(expected, RemixSourceTargets.rejectionOf(input), "failed for <$input>")
            assertNull(RemixSourceTargets.parse(input), "parse must reject <$input>")
        }
    }

    @Test
    fun everyRejectionCarriesDisplayCopy() {
        RemixTargetRejection.entries.forEach { rejection ->
            assertTrue(rejection.message.isNotBlank(), "${rejection.name} needs copy")
        }
    }
}

/**
 * MEM-005a/c preflight battery: one VERIFIED note classifies identically on
 * both platforms — media eligibility, license ask, attribution preview, the
 * source-mismatch guard and the by-id lookup filter.
 */
class RemixSourcePreflightTest {

    private val eventId = "aa".repeat(31) + "11"
    private val pubkey = "bb".repeat(31) + "22"

    private fun note(
        tags: List<List<String>> = emptyList(),
        content: String = "",
        id: String = eventId,
    ): FeedNote = FeedNote.from(
        NostrEvent(
            id = EventId.parse(id)!!,
            pubkey = Pubkey.parse(pubkey)!!,
            createdAt = 1_000L,
            kind = 22,
            tags = tags,
            content = content,
            signature = null,
            receivedFromRelay = null,
        ),
    )

    @Test
    fun videoNotePreflightsAsVideoWithImetaUrl() {
        val n = note(
            tags = listOf(
                listOf("imeta", "url https://blossom.example/b/video.mp4", "m video/mp4", "dim 720x1280", "duration 3.5"),
            ),
        )
        assertEquals("https://blossom.example/b/video.mp4", RemixPreflight.mediaUrlOf(n))
        assertEquals("video", RemixPreflight.mediaKindOf(n))
        assertTrue(RemixPreflight.idMatches(eventId, n))
    }

    @Test
    fun inlineMediaNotePreflightsAsImage() {
        val n = note(content = "gm https://blossom.example/b/meme.png")
        assertEquals("https://blossom.example/b/meme.png", RemixPreflight.mediaUrlOf(n))
        assertEquals("image", RemixPreflight.mediaKindOf(n))
    }

    @Test
    fun noMediaMeansNoRemixSource() {
        val n = note(content = "just words")
        assertNull(RemixPreflight.mediaUrlOf(n))
        assertNull(RemixPreflight.mediaKindOf(n))
    }

    @Test
    fun insecureMediaUrlIsNotLoadable() {
        val n = note(content = "gm http://blossom.example/b/meme.png")
        assertNull(RemixPreflight.mediaUrlOf(n))
        // Loopback stays loadable for local development.
        val local = note(content = "gm http://127.0.0.1:3000/b/meme.png")
        assertEquals("http://127.0.0.1:3000/b/meme.png", RemixPreflight.mediaUrlOf(local))
    }

    @Test
    fun restrictiveLicenseAsksPermissiveLicenseDoesNot() {
        val ask = note(tags = listOf(listOf("license", "bitz/all-reserved")))
        val permissive = note(tags = listOf(listOf("license", "CC0-1.0")))
        assertTrue(RemixPreflight.requiresAsk(ask))
        assertEquals("bitz/all-reserved", RemixPreflight.licenseOf(ask))
        assertTrue(!RemixPreflight.requiresAsk(permissive))
        assertNull(RemixPreflight.licenseOf(note()))
    }

    @Test
    fun mismatchGuardRejectsDifferentEvent() {
        val other = eventId.map { if (it == '1') '2' else it }.joinToString("")
        val n = note(id = other)
        assertTrue(!RemixPreflight.idMatches(eventId, n))
    }

    @Test
    fun attributionPreviewRidesTheSharedTagRule() {
        assertEquals("remix of satoshi", RemixPreflight.attributionPreview("satoshi"))
        val long = "x".repeat(200)
        assertTrue(RemixPreflight.attributionPreview(long).length <= space.bitos.core.feed.RemixRules.ATTRIBUTION_MAX)
    }

    @Test
    fun lookupFilterIsBoundedToTheRequestedId() {
        assertEquals(
            """{"kinds":[1,20,21,22],"ids":["$eventId"],"limit":1}""",
            RemixPreflight.lookupFilter(eventId),
        )
    }
}
