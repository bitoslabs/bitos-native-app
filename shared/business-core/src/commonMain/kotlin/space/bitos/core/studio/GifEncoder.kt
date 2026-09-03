package space.bitos.core.studio

/**
 * Pure GIF89a animated encoder (plan MST-022; verbatim port of the web
 * `gif-encode.ts` — same LZW cadence, same median-cut quantization, so a
 * native-encoded meme and a web-encoded meme are byte-compatible in
 * structure). Compositions are opaque full frames: no transparency or
 * disposal tricks, every frame carries a LOCAL color table and the
 * NETSCAPE2.0 loop-forever extension rides after the logical screen
 * descriptor. Pure byte math over RGBA buffers — decoders and rasters
 * stay native.
 */
object GifLzw {

    /**
     * GIF LZW (omggif-style code-size cadence). Mirrors web
     * `gifLzwEncode` exactly — same clear/end codes, same 4096 reset,
     * same grow-before-insert order — so outputs hash identically.
     */
    fun encode(indices: ByteArray, minCodeSize: Int): ByteArray {
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var next = endCode + 1
        var dict = HashMap<Int, Int>()

        val out = ByteArrayList()
        var bitBuf = 0
        var bitCnt = 0
        fun emit(code: Int) {
            bitBuf = bitBuf or (code shl bitCnt)
            bitCnt += codeSize
            while (bitCnt >= 8) {
                out.add(bitBuf and 0xFF)
                bitBuf = bitBuf ushr 8
                bitCnt -= 8
            }
        }

        emit(clearCode)
        var prefix = indices.getOrElse(0) { 0 }.toInt() and 0xFF
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val cur: Int? = dict[key]
            if (cur != null) {
                prefix = cur
                continue
            }
            emit(prefix)
            if (next == 4096) {
                emit(clearCode)
                dict = HashMap()
                next = endCode + 1
                codeSize = minCodeSize + 1
            } else {
                if (next >= (1 shl codeSize) && codeSize < 12) codeSize += 1
                dict[key] = next
                next += 1
            }
            prefix = k
        }
        emit(prefix)
        emit(endCode)
        if (bitCnt > 0) out.add(bitBuf and 0xFF)
        return out.toByteArray()
    }
}

/** Growable byte sink (common Kotlin has no ArrayList<Byte> boxing win). */
internal class ByteArrayList(initial: Int = 256) {
    private var data = ByteArray(initial)
    private var size = 0

    val count: Int get() = size

    fun add(byte: Int) {
        ensure(size + 1)
        data[size] = byte.toByte()
        size += 1
    }

    fun addAll(bytes: ByteArray) {
        ensure(size + bytes.size)
        bytes.copyInto(data, size)
        size += bytes.size
    }

    fun toByteArray(): ByteArray = data.copyOf(size)

    private fun ensure(needed: Int) {
        if (needed <= data.size) return
        var grown = data.size * 2
        while (grown < needed) grown *= 2
        data = data.copyOf(grown)
    }
}

/**
 * Median-cut quantization over a 5-bit/channel histogram of one frame
 * (web `medianCut` port). Returns the ≤[maxColors] palette (RGB
 * triplets) + per-pixel palette indices; alpha < 128 pixels fold to
 * index 0 (the encoder never emits transparency).
 */
object MedianCut {

    data class Result(
        val palette: ByteArray,
        val indices: ByteArray,
    )

    private class Entry {
        var r = 0
        var g = 0
        var b = 0
        var n = 0
    }

    private class Box(val keys: IntArray) {
        var rs = 0L
        var gs = 0L
        var bs = 0L
        var n = 0L
        val min = IntArray(3) { 255 }
        val max = IntArray(3)
    }

    private fun colorKey(r: Int, g: Int, b: Int): Int =
        ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)

    private fun keyChannel(key: Int, channel: Int): Int = when (channel) {
        0 -> ((key shr 10) and 31) shl 3
        1 -> ((key shr 5) and 31) shl 3
        else -> (key and 31) shl 3
    }

    fun quantize(rgba: ByteArray, maxColors: Int): Result {
        val hist = HashMap<Int, Entry>()
        var i = 0
        while (i + 3 < rgba.size) {
            if (rgba[i + 3].toInt() and 0xFF >= 128) {
                val r = rgba[i].toInt() and 0xFF
                val g = rgba[i + 1].toInt() and 0xFF
                val b = rgba[i + 2].toInt() and 0xFF
                val key = colorKey(r, g, b)
                val entry = hist.getOrPut(key) { Entry() }
                entry.r += r
                entry.g += g
                entry.b += b
                entry.n += 1
            }
            i += 4
        }

        fun boxOf(keys: IntArray): Box {
            val box = Box(keys)
            for (key in keys) {
                val e = hist.getValue(key)
                box.rs += e.r
                box.gs += e.g
                box.bs += e.b
                box.n += e.n
                for (ch in 0..2) {
                    val c = keyChannel(key, ch)
                    if (c < box.min[ch]) box.min[ch] = c
                    if (c > box.max[ch]) box.max[ch] = c
                }
            }
            return box
        }

        var boxes: ArrayList<Box> = ArrayList<Box>()
        if (hist.isNotEmpty()) boxes.add(boxOf(hist.keys.toIntArray()))
        while (boxes.size < maxColors) {
            var best: Box? = null
            var bestRange = -1L
            var bestCh = 0
            for (box in boxes) {
                if (box.keys.size < 2) continue
                for (ch in 0..2) {
                    val range = (box.max[ch] - box.min[ch]).toLong() * box.n
                    if (range > bestRange) {
                        bestRange = range
                        best = box
                        bestCh = ch
                    }
                }
            }
            val split = best ?: break
            val sorted = split.keys.sortedBy { key -> keyChannel(key, bestCh) }
            val mid = sorted.size shr 1
            val replacement = listOf(
                boxOf(sorted.subList(0, mid).toIntArray()),
                boxOf(sorted.subList(mid, sorted.size).toIntArray()),
            )
            boxes = ArrayList(boxes.flatMap { box ->
                if (box === split) replacement else listOf(box)
            })
        }

        val palette = ByteArray(boxes.size * 3)
        val keyIndex = HashMap<Int, Int>()
        boxes.forEachIndexed { index, box ->
            palette[index * 3] = ((box.rs / box.n).toInt()).toByte()
            palette[index * 3 + 1] = ((box.gs / box.n).toInt()).toByte()
            palette[index * 3 + 2] = ((box.bs / box.n).toInt()).toByte()
            box.keys.forEach { key -> keyIndex[key] = index }
        }

        val pixels = rgba.size / 4
        val indices = ByteArray(pixels)
        var p = 0
        i = 0
        while (i + 3 < rgba.size && p < pixels) {
            val alpha = rgba[i + 3].toInt() and 0xFF
            indices[p] = if (alpha < 128) {
                0
            } else {
                (keyIndex[
                    colorKey(
                        rgba[i].toInt() and 0xFF,
                        rgba[i + 1].toInt() and 0xFF,
                        rgba[i + 2].toInt() and 0xFF,
                    ),
                ] ?: 0).toByte()
            }
            i += 4
            p += 1
        }
        return Result(palette, indices)
    }
}

/** One encoded frame: RGBA pixels (row-major, width×height×4) + hold time. */
class GifEncodeFrame(
    val rgba: ByteArray,
    val delayMs: Int,
)

/**
 * Encodes frames as a looping animated GIF89a. Local color tables only
 * (256 colors/frame), delays stored in centiseconds with the 20 ms floor.
 * Deterministic: identical inputs → identical bytes (goldens pin this).
 */
object GifEncoder {

    const val MAX_COLORS = 256

    /** GIF centisecond floor (browsers re-clamp sub-2cs delays anyway). */
    const val MIN_DELAY_CS = 2

    fun encode(frames: List<GifEncodeFrame>, width: Int, height: Int): ByteArray {
        require(frames.isNotEmpty()) { "Nothing to encode — the frame list is empty" }
        require(width > 0 && height > 0) { "GIF canvas must be positive" }
        val expected = width * height * 4
        frames.forEach { frame ->
            require(frame.rgba.size == expected) {
                "frame buffer is ${frame.rgba.size} bytes, expected $expected"
            }
        }

        val out = ByteArrayList(expected / 2)
        fun push16(v: Int) {
            out.add(v and 0xFF)
            out.add((v shr 8) and 0xFF)
        }

        // Header + logical screen (no global table — frames carry local ones).
        byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61).forEach { b -> out.add(b.toInt()) } // GIF89a
        push16(width)
        push16(height)
        out.add(0x70)
        out.add(0)
        out.add(0)
        // NETSCAPE loop-forever extension.
        out.add(0x21)
        out.add(0xFF)
        out.add(0x0B)
        "NETSCAPE2.0".forEach { ch -> out.add(ch.code) }
        out.add(0x03)
        out.add(0x01)
        out.add(0x00)
        out.add(0x00)
        out.add(0x00)

        for (frame in frames) {
            val (palette, indices) = MedianCut.quantize(frame.rgba, MAX_COLORS)

            // Graphic control: disposal 0, delay in centiseconds (≥ 2).
            val delayCs = maxOf(MIN_DELAY_CS, (frame.delayMs + 5) / 10)
            out.add(0x21)
            out.add(0xF9)
            out.add(0x04)
            out.add(0x00)
            push16(delayCs)
            out.add(0x00)
            out.add(0x00)

            // Image descriptor + local color table.
            val colorCount = palette.size / 3
            var bits = 1
            while (1 shl bits < maxOf(2, colorCount)) bits += 1
            val tableEntries = 1 shl bits
            out.add(0x2C)
            push16(0)
            push16(0)
            push16(width)
            push16(height)
            out.add(0x80 or (bits - 1))
            for (index in 0 until tableEntries) {
                val base = index * 3
                out.add(palette.getOrElse(base) { 0 }.toInt() and 0xFF)
                out.add(palette.getOrElse(base + 1) { 0 }.toInt() and 0xFF)
                out.add(palette.getOrElse(base + 2) { 0 }.toInt() and 0xFF)
            }

            // LZW data as ≤255-byte sub-blocks.
            val minCodeSize = maxOf(2, bits)
            val lzw = GifLzw.encode(indices, minCodeSize)
            out.add(minCodeSize)
            var offset = 0
            while (offset < lzw.size) {
                val chunk = minOf(255, lzw.size - offset)
                out.add(chunk)
                out.addAll(lzw.copyOfRange(offset, offset + chunk))
                offset += chunk
            }
            out.add(0x00)
        }

        out.add(0x3B) // trailer
        return out.toByteArray()
    }
}
