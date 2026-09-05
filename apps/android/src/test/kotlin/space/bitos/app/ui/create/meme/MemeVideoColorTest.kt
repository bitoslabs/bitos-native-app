package space.bitos.app.ui.create.meme

import kotlin.test.Test
import kotlin.test.assertEquals
import space.bitos.core.studio.MemeAdjust
import space.bitos.core.studio.MemeLooks

class MemeVideoColorTest {
    @Test fun videoMatrixMatchesSharedColorRules() {
        val pixels = listOf(intArrayOf(0, 0, 0), intArrayOf(255, 255, 255), intArrayOf(31, 127, 219))
        for (look in MemeLooks.ALL) {
            for (adjust in listOf(null, MemeAdjust(0.8f, 1.3f, 0.6f))) {
                val shared = MemeLooks.adjustedMatrixFor(look.id, adjust)
                val gl = MemeVideoColor.glMatrix(shared)
                assertEquals(16, gl.size)
                for (pixel in pixels) {
                    val expected = MemeLooks.applyToPixel(shared, pixel[0], pixel[1], pixel[2])
                    for (channel in 0..2) {
                        val actual = (gl[channel] * pixel[0] + gl[4 + channel] * pixel[1] +
                            gl[8 + channel] * pixel[2] + gl[12 + channel] * 255).coerceIn(0f, 255f)
                        assertEquals(expected[channel].toFloat(), actual, 1.01f, "${look.id}, channel $channel")
                    }
                    assertEquals(1f, gl[15])
                }
            }
        }
    }
}
