import BusinessCore
import XCTest
@testable import BitOS

/**
 * Content-classification adapter contract (AGENTS: shared business changes
 * require native adapter-contract tests): the Swift seam over the
 * XCFramework must agree with the web `content-classification.ts` rule
 * locked in the shared common suite — serialized channel rosters are
 * machine traffic, `udal-*` coordination tags are not topics, and the
 * persisted protocol-notes opt-in gates the feed windows.
 */
final class ContentClassificationTests: XCTestCase {
    private let client = FrameworkBusinessCoreClient()

    // MARK: - Roster rule (web fixtures verbatim)

    func testRecognizesSerializedChannelRosters() {
        XCTAssertTrue(client.isProtocolPayload("channel:__roster\n" + String(repeating: "ab", count: 80)))
        XCTAssertTrue(client.isProtocolPayload("channel: __roster\n" + String(repeating: "ab", count: 48)))
    }

    func testDoesNotHideProseThatMentionsAChannelOrHash() {
        XCTAssertFalse(client.isProtocolPayload("channel: __roster\nWelcome to the group."))
        XCTAssertFalse(client.isProtocolPayload("The build hash is " + String(repeating: "ab", count: 32)))
        // Prose (even JSON-ish) is reader content — never the old broad
        // leading-brace heuristic.
        XCTAssertFalse(client.isProtocolPayload(#"{"kind":"token","amount":1}"#))
        XCTAssertFalse(client.isProtocolPayload("gm nostr"))
    }

    // MARK: - Machine tags

    func testMachineTagsNeverCarryHumanTopics() {
        XCTAssertTrue(client.isMachineTag("udal-friend-aede0a98e7fd3ffef77db169c0ccaaa1"))
        XCTAssertTrue(client.isMachineTag("udal-peer-2348e984dab2c63dfbdab100aa1a3974"))
        XCTAssertFalse(client.isMachineTag("nostr"))
        XCTAssertFalse(client.isMachineTag("bitcoin-price"))
        XCTAssertFalse(client.isMachineTag("udal"))
        XCTAssertFalse(client.isMachineTag("udal-friend-abc")) // too short → not a bot id
    }

    func testHumanTagsFiltersMachineTagsOutOfAMixedList() {
        XCTAssertEqual(
            client.humanTags(["nostr", "udal-friend-aede0a98e7fd3ffef77db169c0ccaaa1", "asknostr"]),
            ["nostr", "asknostr"]
        )
    }

    // MARK: - Feed window gate (feedPreferences parity)

    func testProtocolOptInReAdmitsRostersIntoTheAllWindows() {
        let roster = FeedNote(
            id: "1", pubkey: "aa", content: "channel:__roster\n" + String(repeating: "ab", count: 64),
            createdAt: 1, kind: 1, replyTo: nil, hashtags: [], mentions: [],
            mediaUrls: [], isProtocolPayload: true
        )
        // All ordinal 0; hidden by default, visible after the opt-in.
        XCTAssertFalse(client.feedFilterMatches(note: roster, filterOrdinal: 0, ownPubkeyHex: nil, likedIds: [], showProtocolNotes: false))
        XCTAssertTrue(client.feedFilterMatches(note: roster, filterOrdinal: 0, ownPubkeyHex: nil, likedIds: [], showProtocolNotes: true))
    }
}
