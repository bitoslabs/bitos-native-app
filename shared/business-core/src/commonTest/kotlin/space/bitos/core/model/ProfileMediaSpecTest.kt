package space.bitos.core.model

import kotlin.test.Test
import kotlin.test.assertTrue

class ProfileMediaSpecTest {
    @Test
    fun targetsAreSaneAndBounded() {
        assertTrue(ProfileMediaSpec.AVATAR_SIZE in 128..2048)
        assertTrue(ProfileMediaSpec.BANNER_WIDTH in 512..4096)
        assertTrue(ProfileMediaSpec.BANNER_HEIGHT >= 128)
        assertTrue(ProfileMediaSpec.BANNER_WIDTH > ProfileMediaSpec.BANNER_HEIGHT)
        assertTrue(ProfileMediaSpec.MAX_ENCODED_BYTES in 512 * 1024..16 * 1024 * 1024)
    }
}
