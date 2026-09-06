package space.bitos.core.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Live-frame contract check for the You following projection: the exact
 * relay frame served for a real account (captured verbatim from
 * wss://nos.lol, 2026-09-06) must round-trip the same
 * decode → verify → project seam the iOS store calls
 * (`contactListAuthors`). Guards against codec/limit regressions that would
 * silently zero the Following count while the frame still passes the pool
 * gate.
 */
class ContactListLiveFrameTest {

    private val account = "e4ae5f87cc744e4eaf6f640c4f1b8e37e3229c0c993ab211053405c484869a93"
    private val followed = "5105851c67bb2765edc069bb027d64ae501e4657048958bcf887f3983a96c09c"

    private val liveFrame =
        """["EVENT","bitos-contacts",{"content":"","created_at":1788682182,""" +
            """"id":"b594c2aa60c9ec2e5f8c298f702758377c7e5f87d96e75421a507e15b5ed822c",""" +
            """"kind":3,"pubkey":"$account",""" +
            """"sig":"bae07230e938f295b5a770d69d9038c2337c3923dbb044ebd7ce9d00717abc16e8e3a484b1f1810bd93f07b3ce13a2762375cfe3dc2b23ad78b6c7d5d121245f",""" +
            """"tags":[["p","$followed"]]}]"""

    @Test
    fun liveContactFrameProjectsThroughTheBridgeSeam() {
        val authors = BusinessCoreBridge().contactListAuthors(liveFrame, "wss://nos.lol")
        assertNotNull(authors, "the live frame must decode + verify + project")
        assertEquals(listOf(followed), authors)
    }

    @Test
    fun liveContactFrameDecodesThroughThePoolGateSeam() {
        val decoded = BusinessCoreBridge().decodeEventWithSubscriptionId(liveFrame, "wss://nos.lol")
        assertNotNull(decoded)
        assertEquals(3, decoded.event.kind)
        assertEquals(account, decoded.event.pubkey)
        assertEquals(followed, decoded.event.tags.first().last())
    }
}
