package space.bitos.core.model

/**
 * Profile media targets (APP-013/APP-018 upload parity): picked images are
 * center-cropped to the field's aspect and downscaled before upload, so a
 * phone photo becomes a right-sized avatar/banner (bytes bounded).
 */
object ProfileMediaSpec {
    /** Square avatar (center-crop). */
    const val AVATAR_SIZE = 512

    /** Wide banner (center-crop 3:1). */
    const val BANNER_WIDTH = 1500
    const val BANNER_HEIGHT = 500

    /** Upload byte bound after downscale (JPEG re-encode). */
    const val MAX_ENCODED_BYTES = 4 * 1024 * 1024
}
