package space.bitos.app.ui.create.meme

import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix

internal object MemeVideoAudio {
    fun matrix(channels: Int, volume: Float) = ChannelMixingMatrix(channels, channels,
        FloatArray(channels * channels) { index ->
            if (index / channels == index % channels) volume.coerceIn(0f, 2f) else 0f
        },
    )

    fun gain(volume: Float): ChannelMixingAudioProcessor = ChannelMixingAudioProcessor().apply {
        // Preserve each channel; do not fold stereo/surround into mono.
        for (channels in 1..8) {
            putChannelMixingMatrix(matrix(channels, volume))
        }
    }
}
