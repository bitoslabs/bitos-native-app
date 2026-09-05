package space.bitos.app.ui.create.meme

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class MemeVideoAudioTest {
    @Test fun exportGainPreservesStereoChannelsAndScalesPcm() {
        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        input.putShort(12000).putShort(-8000).flip()
        val output = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        androidx.media3.common.audio.AudioMixingUtil.mix(
            input, format, output, format, MemeVideoAudio.matrix(2, 0.5f), 1, false, true,
        )
        output.flip()
        assertEquals(6000, output.short.toInt())
        assertEquals(-4000, output.short.toInt())
    }
}
