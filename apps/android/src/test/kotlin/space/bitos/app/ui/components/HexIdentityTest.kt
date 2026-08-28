package space.bitos.app.ui.components

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * APP-022 hex identity contract (unified feature spec §2.5/§4): the
 * flat-top hexagon geometry shared with the web `.hex-clip` and the iOS
 * `HexIdentity`, plus deterministic identity-gradient derivation.
 */
class HexIdentityTest {

    @Test
    fun verticesMatchCssHexClipPolygon() {
        // Web `.hex-clip` / Flutter `hexPoints`: regular flat-top hexagon with
        // a 6.7% vertical inset (values compared with ULP tolerance — Float
        // arithmetic like `100f * 0.067f` is not bit-identical to `6.7f`).
        val v = HexIdentity.vertices(100f, 100f)
        val expected = listOf(
            Offset(25f, 6.7f),
            Offset(75f, 6.7f),
            Offset(100f, 50f),
            Offset(75f, 93.3f),
            Offset(25f, 93.3f),
            Offset(0f, 50f),
        )
        assertEquals(expected.size, v.size)
        expected.zip(v).forEach { (e, a) ->
            assertEquals(e.x, a.x, 0.001f)
            assertEquals(e.y, a.y, 0.001f)
        }
    }

    @Test
    fun identityColorsDeterministicAndDistinct() {
        val key = "a1b2c3d4" + "0".repeat(56)
        val a = HexIdentity.identityColors(key)
        assertEquals(a, HexIdentity.identityColors(key))
        assertNotEquals(a.start, a.end)

        val other = HexIdentity.identityColors("00000010" + "0".repeat(56))
        assertNotEquals(a.start, other.start)
    }

    @Test
    fun highSeedPrefixDoesNotCollapseToZeroHue() {
        // "ffffffff" overflows Int parsing; Long parse must keep a stable hue
        // (regression: the circle identicon silently fell back to seed 0).
        val high = HexIdentity.identityColors("ffffffff" + "0".repeat(56))
        val zero = HexIdentity.identityColors("00000000" + "0".repeat(56))
        assertNotEquals(high.start, zero.start)
    }

    @Test
    fun nonHexPrefixFallsBackToDeterministicZeroSeed() {
        assertEquals(
            HexIdentity.identityColors("zz-not-hex"),
            HexIdentity.identityColors("0000zz"),
        )
    }
}
