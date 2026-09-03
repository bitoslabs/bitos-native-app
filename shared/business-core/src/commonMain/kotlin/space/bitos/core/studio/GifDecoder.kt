package space.bitos.core.studio

/**
 * Pure GIF89a frame decoder (plan MST-020; port of the web `gif.ts`
 * pure-JS fallback decoder — header/color tables/LZW image blocks,
 * interlacing, transparency and disposal compositing). Emits composited
 * RGBA frames (row-major, width×height×4) so the stage preview, the
 * rasters and the [GifEncoder] share one pixel format. Decode is
 * tolerant-but-bounded: truncated streams stop at the last whole frame,
 * oversize inputs reject, junk GIFs → null (the editor treats that as a
 * still image path, never a crash).
 */
object GifDecoder {

    /** Hostile-input bound (matches the project wire bound). */
    const val MAX_INPUT_BYTES = 24 * 1024 * 1024

    const val MIN_DURATION_SEC = 0.1

    class Frame(
        /** Composited RGBA pixels of the whole canvas (with disposal applied). */
        val rgba: ByteArray,
        /** Encoded hold (ms) — sub-2cs source delays became 100 (web heuristic). */
        val delayMs: Int,
    )

    class Decoded(
        val width: Int,
        val height: Int,
        val frames: List<Frame>,
    ) {
        val durationSec: Double = maxOf(
            MIN_DURATION_SEC,
            frames.fold(0.0) { sum, frame -> sum + frame.delayMs / 1000.0 },
        )

        /** Timing rows for the shared [GifExportPlan]. */
        fun timings(): List<GifExportPlan.FrameTiming> {
            val rows = mutableListOf<GifExportPlan.FrameTiming>()
            var at = 0.0
            frames.forEach { frame ->
                rows += GifExportPlan.FrameTiming(atSec = at, durationSec = frame.delayMs / 1000.0)
                at += frame.delayMs / 1000.0
            }
            return rows
        }
    }

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < 13 || bytes.size > MAX_INPUT_BYTES) return null
        val sig = bytes.decodeToString(0, 6)
        if (!sig.startsWith("GIF8") || !(sig.endsWith("7a") || sig.endsWith("9a"))) return null
        val width = u16(bytes, 6)
        val height = u16(bytes, 8)
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) return null
        val packed = bytes[10].toInt() and 0xFF
        var pos = 13
        var gct: ByteArray? = null
        if (packed and 0x80 != 0) {
            val size = 2 shl (packed and 7)
            if (pos + size * 3 > bytes.size) return null
            gct = bytes.copyOfRange(pos, pos + size * 3)
            pos += size * 3
        }

        val canvas = ByteArray(width * height * 4)
        val frames = mutableListOf<Frame>()
        var gceDelayCs = 0
        var gceDisposal = 0
        var gceTransparent = -1

        while (pos < bytes.size) {
            val block = bytes[pos].toInt() and 0xFF
            when {
                block == 0x3B -> break // trailer

                block == 0x21 -> { // extension
                    val label = bytes.getOrNull(pos + 1)?.toInt()?.and(0xFF) ?: break
                    pos += 2
                    if (label == 0xF9 && pos < bytes.size && (bytes[pos].toInt() and 0xFF) == 4) {
                        val gcePacked = bytes.getOrNull(pos + 1)?.toInt()?.and(0xFF) ?: 0
                        gceDelayCs = u16(bytes, pos + 2)
                        gceDisposal = (gcePacked shr 2) and 7
                        gceTransparent = if (gcePacked and 1 != 0) {
                            bytes.getOrNull(pos + 4)?.toInt()?.and(0xFF) ?: -1
                        } else {
                            -1
                        }
                    }
                    // Skip sub-blocks (length-prefixed, 0-terminated).
                    while (pos < bytes.size && bytes[pos].toInt() != 0) {
                        pos += (bytes[pos].toInt() and 0xFF) + 1
                    }
                    pos += 1
                }

                block != 0x2C -> pos += 1 // unknown block — resync

                else -> { // image descriptor
                    pos += 1
                    if (pos + 9 > bytes.size) break
                    val left = u16(bytes, pos)
                    val top = u16(bytes, pos + 2)
                    val iw = u16(bytes, pos + 4)
                    val ih = u16(bytes, pos + 6)
                    val ip = bytes[pos + 8].toInt() and 0xFF
                    pos += 9
                    var table = gct
                    if (ip and 0x80 != 0) {
                        val size = 2 shl (ip and 7)
                        if (pos + size * 3 > bytes.size) break
                        table = bytes.copyOfRange(pos, pos + size * 3)
                        pos += size * 3
                    }
                    if (table == null) return null
                    if (iw <= 0 || ih <= 0) continue
                    val minCodeSize = bytes.getOrNull(pos)?.toInt()?.and(0xFF) ?: break
                    pos += 1
                    if (minCodeSize !in 2..11) continue

                    // Concatenate LZW sub-blocks.
                    var scan = pos
                    var lzwLen = 0
                    while (scan < bytes.size && bytes[scan].toInt() != 0) {
                        lzwLen += bytes[scan].toInt() and 0xFF
                        scan += (bytes[scan].toInt() and 0xFF) + 1
                    }
                    val lzw = ByteArray(lzwLen)
                    var write = 0
                    while (pos < bytes.size && bytes[pos].toInt() != 0) {
                        val len = bytes[pos].toInt() and 0xFF
                        if (pos + 1 + len > bytes.size) break
                        bytes.copyInto(lzw, write, pos + 1, pos + 1 + len)
                        write += len
                        pos += len + 1
                    }
                    pos += 1

                    val indices = lzwDecode(minCodeSize, lzw, iw * ih)

                    // Disposal 3 restores the previous canvas after this frame.
                    var saved: ByteArray? = null
                    if (gceDisposal == 3) saved = canvas.copyOf()

                    // Paint the patch (transparent index → alpha 0).
                    val rowOrder = if (ip and 0x40 != 0) interlaceRowOrder(ih) else null
                    for (y in 0 until ih) {
                        val destY = rowOrder?.get(y) ?: y
                        for (x in 0 until iw) {
                            val idx = indices.getOrNull(y * iw + x) ?: continue
                            if (idx == gceTransparent) continue
                            val c = idx * 3
                            val o = (destY * width + left + x) * 4
                            if (o < 0 || o + 3 >= canvas.size) continue
                            if (left + x >= width) continue
                            canvas[o] = table.getOrElse(c) { 0 }
                            canvas[o + 1] = table.getOrElse(c + 1) { 0 }
                            canvas[o + 2] = table.getOrElse(c + 2) { 0 }
                            canvas[o + 3] = 0xFF.toByte()
                        }
                    }

                    // Browsers render delays under 2cs as 10cs.
                    val delayCs = if (gceDelayCs < 2) 10 else gceDelayCs
                    frames += Frame(canvas.copyOf(), delayCs * 10)

                    when (gceDisposal) {
                        2 -> { // restore to background (transparent) over the patch rect
                            for (y in 0 until ih) {
                                for (x in 0 until iw) {
                                    val o = ((top + y) * width + left + x) * 4
                                    if (o + 3 < canvas.size && left + x < width) {
                                        canvas[o + 3] = 0
                                    }
                                }
                            }
                        }

                        3 -> saved?.let { savedFrame ->
                            savedFrame.copyInto(canvas)
                        }
                    }
                    gceDelayCs = 0
                    gceDisposal = 0
                    gceTransparent = -1
                }
            }
        }

        if (frames.isEmpty()) return null
        return Decoded(width, height, frames)
    }

    private fun u16(bytes: ByteArray, at: Int): Int {
        if (at + 1 >= bytes.size) return 0
        return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }

    /** GIF LZW decompression → color indices (web `gifLzw` port). */
    fun lzwDecode(minCodeSize: Int, bytes: ByteArray, maxIndices: Int): IntArray {
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var dict = ArrayList<IntArray>()
        fun resetDict() {
            dict = ArrayList()
            for (i in 0 until clearCode) dict += intArrayOf(i)
            dict += IntArray(0) // clear
            dict += IntArray(0) // end
            codeSize = minCodeSize + 1
        }
        resetDict()

        val out = IntArray(maxIndices.coerceAtLeast(bytes.size * 2))
        var outLen = 0
        var prev: IntArray? = null
        var bitPos = 0
        fun readCode(): Int {
            var code = 0
            for (i in 0 until codeSize) {
                val byte = bytes.getOrNull(bitPos shr 3)?.toInt()?.and(0xFF) ?: 0
                if (byte and (1 shl (bitPos and 7)) != 0) code = code or (1 shl i)
                bitPos += 1
            }
            return code
        }
        while (outLen < out.size) {
            val code = readCode()
            if (bitPos > bytes.size * 8 + codeSize) break // truncated guard
            if (code == clearCode) {
                resetDict()
                prev = null
                continue
            }
            if (code == endCode) break
            val entry: IntArray = when {
                code < dict.size -> dict[code]
                prev != null -> prev!! + prev!![0]
                else -> break // corrupt stream
            }
            entry.forEach { b ->
                if (outLen < out.size) {
                    out[outLen] = b
                    outLen += 1
                }
            }
            prev?.let { p ->
                dict += p + entry[0]
                if (dict.size == (1 shl codeSize) && codeSize < 12) codeSize += 1
            }
            prev = entry
        }
        return out.copyOf(outLen)
    }

    /** Interlaced rows arrive in 4 passes (0/8, 4/8, 2/4, 1/2). */
    fun interlaceRowOrder(height: Int): IntArray {
        val order = IntArray(height)
        var write = 0
        intArrayOf(0, 4, 2, 1).forEachIndexed { passIndex, start ->
            val step = intArrayOf(8, 8, 4, 2)[passIndex]
            var y = start
            while (y < height) {
                order[write] = y
                write += 1
                y += step
            }
        }
        return order
    }
}
