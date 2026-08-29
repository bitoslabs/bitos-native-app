package space.bitos.app.ui.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/**
 * Profile image prep (legacy `ImageCropEditor` default-crop parity):
 * center-crop to the target aspect + downscale to the exact pixel bound,
 * re-encode as JPEG within the shared [space.bitos.core.model.ProfileMediaSpec]
 * byte budget. EXIF orientation is applied before cropping.
 */
object ProfileImagePrep {

    /** Center-crop + scale to [targetW]×[targetH]; null when undecodable. */
    fun cropScale(bytes: ByteArray, targetW: Int, targetH: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Subsample toward the target for memory efficiency.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val oriented = applyExif(bytes, decoded)

        val srcW = oriented.width
        val srcH = oriented.height
        val targetAspect = targetW.toFloat() / targetH
        val cropW: Int
        val cropH: Int
        if (srcW.toFloat() / srcH > targetAspect) {
            cropH = srcH
            cropW = (srcH * targetAspect).toInt()
        } else {
            cropW = srcW
            cropH = (srcW / targetAspect).toInt()
        }
        val x = (srcW - cropW) / 2
        val y = (srcH - cropH) / 2
        val cropped = Bitmap.createBitmap(oriented, x, y, cropW, cropH)
        val scaled = if (cropped.width != targetW || cropped.height != targetH) {
            Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
        } else {
            cropped
        }
        val out = ByteArrayOutputStream()
        var quality = 88
        val budget = space.bitos.core.model.ProfileMediaSpec.MAX_ENCODED_BYTES
        while (true) {
            out.reset()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= budget || quality <= 40) break
            quality -= 12
        }
        return out.toByteArray()
    }

    private fun applyExif(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val exif = runCatching { ExifInterface(bytes.inputStream()) }.getOrNull() ?: return bitmap
        val rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
