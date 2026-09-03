package space.bitos.app.ui.create.meme

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlin.math.max
import space.bitos.core.studio.MemeExportRules
import space.bitos.core.studio.MemeFontSlot
import space.bitos.core.studio.MemeProject

/**
 * Off-screen raster + device save for the Quick MEM editor (plan MST-016):
 * paints the shared [MemeExportRules] draw plan with android.graphics so
 * the export geometry equals the stage preview, then lands the PNG in
 * MediaStore (`Pictures/BitOS`, modern API — minSdk 29). Rendering is a
 * fast single-shot job on the caller's IO dispatcher (idempotent per tap;
 * cancellable long exports arrive with the GIF/video waves).
 */
object MemeRaster {

    /** Decodes the pick at a sample size near the export long edge. */
    fun decodeForExport(resolver: android.content.ContentResolver, uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val sample = max(1, max(bounds.outWidth, bounds.outHeight) / MemeExportRules.LONG_EDGE)
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Renders asset + overlay plan at the export resolution (even dims).
     *  The grade (MST-043) burns into the MEDIA only — overlays draw
     *  unfiltered after it, exactly like the web canvas (`ctx.filter`). */
    fun render(source: Bitmap, project: MemeProject): Bitmap {
        val (width, height) = MemeExportRules.outputSize(source.width, source.height)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(source, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), basePaint(project))
        drawStrokes(canvas, MemeExportRules.drawingPlan(project, width, height))
        MemeExportRules.exportPlan(project, width, height).forEach { item ->
            drawItem(canvas, item)
        }
        return out
    }

    /** Media paint with the look's composed color matrix (identity = none). */
    private fun basePaint(project: MemeProject): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            if (project.lookId != null) {
                colorFilter = android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix(space.bitos.core.studio.MemeLooks.matrixFor(project.lookId)),
                )
            }
        }

    /**
     * GIF export path (MST-022): renders ONE frame's composition to RGBA
     * bytes (row-major, width×height×4) at the given canvas size — the
     * shared GifEncoder input format. The size ladder calls this per frame
     * at each candidate canvas.
     */
    fun renderFrameRgba(
        frame: Bitmap,
        project: MemeProject,
        width: Int,
        height: Int,
    ): ByteArray {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(frame, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), basePaint(project))
        drawStrokes(canvas, MemeExportRules.drawingPlan(project, width, height))
        MemeExportRules.exportPlan(project, width, height).forEach { item ->
            drawItem(canvas, item)
        }
        val argb = IntArray(width * height)
        out.getPixels(argb, 0, width, 0, 0, width, height)
        out.recycle()
        val rgba = ByteArray(argb.size * 4)
        argb.forEachIndexed { index, pixel ->
            val base = index * 4
            rgba[base] = ((pixel shr 16) and 0xFF).toByte()
            rgba[base + 1] = ((pixel shr 8) and 0xFF).toByte()
            rgba[base + 2] = (pixel and 0xFF).toByte()
            rgba[base + 3] = ((pixel shr 24) and 0xFF).toByte()
        }
        return rgba
    }

    /** Video-overlay layer painting (same geometry as stills — WYSIWYG).
     *  [fx] is the frame's timed transform (windows + motion effects). */
    internal fun drawExportItem(
        canvas: Canvas,
        item: MemeExportRules.MemeExportItem,
        imageFor: ((String) -> Bitmap?)? = null,
        fx: space.bitos.core.studio.MemeFxRules.FxTransform = space.bitos.core.studio.MemeFxRules.IDENTITY,
    ) {
        drawItem(canvas, item, imageFor, fx)
    }

    private fun drawItem(
        canvas: Canvas,
        item: MemeExportRules.MemeExportItem,
        imageFor: ((String) -> Bitmap?)? = null,
        fx: space.bitos.core.studio.MemeFxRules.FxTransform = space.bitos.core.studio.MemeFxRules.IDENTITY,
    ) {
        // Frame transform: fx offsets the center (dx/dy as canvas
        // fractions), then total rotation + fx scale pivot around the
        // (moved) item center — the exact math the stage applies.
        val centerX = item.centerX + fx.dx * canvas.width
        val centerY = item.centerY + fx.dy * canvas.height
        val alpha255 = (fx.alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(item.rotationDeg + fx.rotateRad * 180f / Math.PI.toFloat())
        canvas.scale(fx.scale, fx.scale)
        canvas.translate(-item.centerX, -item.centerY)
        if (item.image) {
            drawImageItem(canvas, item, imageFor ?: return, alpha255)
            canvas.restore()
            return
        }
        val lineHeight = item.fontSizePx * MemeExportRules.LINE_HEIGHT
        val base = textPaint(item, alpha255)
        item.lines.forEachIndexed { index, line ->
            // Vertical centering around the overlay point + a ~1/3-em lift
            // so the baseline sits where drawText wants it.
            val baseline = item.centerY -
                (item.lines.size - 1) * lineHeight / 2f +
                index * lineHeight +
                item.fontSizePx / 3f
            if (item.outlinePx > 0f) {
                val stroke = Paint(base).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = item.outlinePx
                    color = Color.BLACK
                }
                canvas.drawText(line, item.centerX, baseline, stroke)
            }
            canvas.drawText(line, item.centerX, baseline, base)
        }
        canvas.restore()
    }

    /**
     * Image layers (video-mode source insert): fontSizePx is the layer's
     * target height; width keeps the bitmap aspect. An unresolvable asset
     * paints nothing rather than crashing the export.
     */
    private fun drawImageItem(
        canvas: Canvas,
        item: MemeExportRules.MemeExportItem,
        imageFor: (String) -> Bitmap?,
        alpha255: Int,
    ) {
        val bitmap = item.assetId?.let(imageFor) ?: return
        val height = item.fontSizePx
        val width = height * (bitmap.width.toFloat() / bitmap.height.toFloat())
        val left = item.centerX - width / 2f
        val top = item.centerY - height / 2f
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.alpha = alpha255
        canvas.drawBitmap(
            bitmap,
            null,
            android.graphics.RectF(left, top, left + width, top + height),
            paint,
        )
    }

    private fun textPaint(item: MemeExportRules.MemeExportItem, alpha255: Int = 255): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = typefaceFor(item.font)
        textSize = item.fontSizePx
        color = item.color.toInt()
        if (alpha255 != 255) alpha = alpha255
        if (item.shadow) {
            // Stage parity: offset ≈ 0.06 em, blur ≈ 0.125 em (3/6 px @ 48).
            setShadowLayer(
                item.fontSizePx * 0.125f,
                item.fontSizePx * 0.06f,
                item.fontSizePx * 0.06f,
                Color.BLACK,
            )
        }
    }

    /** Semantic font slots → licensed faces (plan §8: boldest system faces). */
    private fun typefaceFor(slot: MemeFontSlot): Typeface = when (slot) {
        MemeFontSlot.IMPACT -> Typeface.create("sans-serif-black", Typeface.NORMAL)
        MemeFontSlot.SANS -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        MemeFontSlot.SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        MemeFontSlot.MONO -> Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    /**
     * Pen strokes (V2 Draw chip): shared-geometry polylines, round
     * caps/joins — the stage preview and every raster path paint the
     * same shapes (WYSIWYG). Call BEFORE overlay items (ink sits under
     * captions).
     */
    internal fun drawStrokes(
        canvas: Canvas,
        strokes: List<MemeExportRules.MemeStrokePaint>,
    ) {
        strokes.forEach { stroke ->
            if (stroke.pointsPx.size < 4) return@forEach
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke.color.toInt()
                style = Paint.Style.STROKE
                strokeWidth = stroke.widthPx
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val path = android.graphics.Path()
            path.moveTo(stroke.pointsPx[0], stroke.pointsPx[1])
            var index = 2
            while (index + 1 < stroke.pointsPx.size) {
                path.lineTo(stroke.pointsPx[index], stroke.pointsPx[index + 1])
                index += 2
            }
            canvas.drawPath(path, paint)
        }
    }

    /**
     * MediaStore insert for pre-encoded bytes (GIF exports) — same
     * pending-flag round-trip and failed-write cleanup as [savePng].
     */
    fun saveMediaFile(context: Context, bytes: ByteArray, mimeType: String, fileName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BitOS")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore rejected the insert")
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(bytes)
                stream.flush()
            } ?: error("Output stream unavailable")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    /** Video exports land in Movies/BitOS (MediaStore.Video). */
    fun saveVideoFile(context: Context, bytes: ByteArray, fileName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "$fileName.mp4")
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                android.provider.MediaStore.Video.Media.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_MOVIES + "/BitOS",
            )
            put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: error("MediaStore rejected the insert")
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(bytes)
                stream.flush()
            } ?: error("Output stream unavailable")
            values.clear()
            values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    /** MediaStore insert (Pictures/BitOS) — throws on hostile storage state. */
    fun savePng(context: Context, bitmap: Bitmap, fileName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BitOS")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore rejected the insert")
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "PNG encode failed" }
            } ?: error("Output stream unavailable")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }
}
