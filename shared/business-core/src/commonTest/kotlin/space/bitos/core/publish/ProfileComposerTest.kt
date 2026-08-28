package space.bitos.core.publish

import space.bitos.core.model.NostrKinds
import space.bitos.core.model.ProfileMetadata
import space.bitos.core.model.EventId
import space.bitos.core.model.Pubkey
import space.bitos.core.model.RelayUrl
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileComposerTest {

    private val author = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
    private val composer = NoteComposer(clock = { 1_710_000_000 })

    @Test
    fun composesKind0WithEscapedJson() {
        val profile = composer.composeProfileMetadata(
            authorPubkey = author,
            name = "satoshi",
            displayName = "Satoshi ₿",
            about = "Building on Nostr",
            picture = "https://cdn.example/avatar.png",
            nip05 = "satoshi@bitos.space",
            lud16 = "satoshi@wallet.example",
        )!!
        assertEquals(NostrKinds.PROFILE_METADATA, profile.kind)
        assertTrue(profile.content.contains(""""name":"satoshi""""))
        assertTrue(profile.content.contains(""""display_name":"Satoshi ₿""""))
        assertTrue(profile.content.contains(""""lud16":"satoshi@wallet.example""""))
        // Round trip: parses back through the ProfileMetadata projection.
        val frame = composer.publishMessage(profile, "cc".repeat(64))!!
        val decoded = NostrEventCodec.decodeClientEventFrame(Sha256EventHasher, frame, RelayUrl.parse("wss://relay.test"))
        assertEquals(NostrKinds.PROFILE_METADATA, decoded.kind)
        val metadata = ProfileMetadata.parse(decoded)!!
        assertEquals("Satoshi ₿", metadata.bestDisplayName)
        assertEquals("satoshi@wallet.example", metadata.lud16)
    }

    @Test
    fun emptyProfileIsMinimalJson() {
        val profile = composer.composeProfileMetadata(author, "", "", "", "", "", "")!!
        assertEquals("{}", profile.content)
    }

    @Test
    fun rejectsInvalidProfiles() {
        assertNull(composer.composeProfileMetadata("zz", "a", "", "", "", "", ""))
        assertNull(composer.composeProfileMetadata(author, "a".repeat(300), "", "", "", "", ""))
        assertNull(composer.composeProfileMetadata(author, "", "", "x".repeat(2000), "", "", ""))
        assertNull(composer.composeProfileMetadata(author, "", "", "", "http://insecure", "", ""))
        assertNull(composer.composeProfileMetadata(author, "", "", "", "", "", "not-an-address"))
    }
}
