package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals

class MemeUploadRoutingTest {
    @Test
    fun smallVideosRequireBitOSAndBlossomButLargeVideosUseBitOSOnly() {
        assertEquals(
            listOf(MemeUploadRouting.Destination.BITOS_API, MemeUploadRouting.Destination.BLOSSOM),
            MemeUploadRouting.destinations("video/mp4", MemeUploadRouting.DUAL_UPLOAD_MAX_BYTES - 1),
        )
        assertEquals(
            listOf(MemeUploadRouting.Destination.BITOS_API),
            MemeUploadRouting.destinations("video/mp4", MemeUploadRouting.DUAL_UPLOAD_MAX_BYTES),
        )
        assertEquals(emptyList(), MemeUploadRouting.destinations("image/png", 1_024))
    }
}
