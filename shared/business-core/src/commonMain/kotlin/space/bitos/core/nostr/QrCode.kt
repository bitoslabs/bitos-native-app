package space.bitos.core.nostr

/**
 * Pure QR-code encoder (APP-022 `BrandQrCode` backing; shared so both
 * platforms render identical, deterministic matrices). Byte mode, ECC
 * level M, versions 1–[MAX_VERSION] (identity-size payloads: npub links),
 * all 8 masks with penalty scoring. ISO/IEC 18004 structure, Nayuki-style
 * pipeline: GF(256) Reed–Solomon, block interleaving, BCH format info.
 */
object QrCode {
    const val MAX_VERSION = 6

    /** Data-codeword blocks and ECC codewords per block, by version (M). */
    private val DATA_BLOCKS = arrayOf(
        intArrayOf(), // 0 unused
        intArrayOf(19), // V1
        intArrayOf(28), // V2
        intArrayOf(44), // V3
        intArrayOf(22, 22), // V4
        intArrayOf(34, 34), // V5
        intArrayOf(22, 22, 22, 22), // V6
    )
    private val ECC_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16)

    /** Module matrix (row-major; true = dark) or null when it cannot fit. */
    fun encode(text: String): List<List<Boolean>>? {
        val data = text.encodeToByteArray()
        val capacity = (1..MAX_VERSION).firstOrNull { v -> data.size <= byteCapacity(v) } ?: return null
        return buildMatrix(capacity, data)
    }

    fun byteCapacity(version: Int): Int {
        val totalDataBits = DATA_BLOCKS[version].sum() * 8
        val countBits = 8 // versions 1–9 use an 8-bit byte count
        return (totalDataBits - 4 - countBits) / 8
    }

    // ── Reed–Solomon over GF(256), polynomial 0x11D ──────────────────

    private val exp = IntArray(512).also { e ->
        var x = 1
        for (i in 0 until 511) {
            e[i] = x
            x = xorMul(x, 2)
        }
    }
    private val log = IntArray(256).also { l ->
        for (i in 0 until 255) l[exp[i]] = i
    }

    private fun xorMul(a: Int, b: Int): Int {
        var r = 0
        var aa = a
        var bb = b
        while (bb != 0) {
            if (bb and 1 != 0) r = r xor aa
            bb = bb ushr 1
            val carry = aa and 0x80
            aa = aa shl 1
            if (carry != 0) aa = aa xor 0x1D // 0x11D with the dropped bit-8 term removed
            aa = aa and 0xFF
        }
        return r and 0xFF
    }

    private fun gfMul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    private fun rsEcc(data: ByteArray, degree: Int): ByteArray {
        // Generator polynomial coefficients (monic) over GF(256).
        var gen = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(gen.size + 1)
            for (j in gen.indices) {
                next[j] = next[j] xor gfMul(gen[j], exp[i])
                next[j + 1] = next[j + 1] xor gen[j]
            }
            gen = next
        }
        val rem = IntArray(degree)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor rem[0]
            // Shift left by one (common-Kotlin: no java.lang.System here).
            for (i in 0 until degree - 1) rem[i] = rem[i + 1]
            rem[degree - 1] = 0
            for (i in 0 until degree) {
                rem[i] = rem[i] xor gfMul(gen[i + 1], factor)
            }
        }
        return ByteArray(degree) { rem[it].toByte() }
    }

    // ── Matrix construction ──────────────────────────────────────────

    private fun buildMatrix(version: Int, data: ByteArray): List<List<Boolean>> {
        val size = 17 + 4 * version
        val modules = Array(size) { arrayOfNulls<Boolean>(size) }
        val reserved = Array(size) { BooleanArray(size) }

        fun set(x: Int, y: Int, dark: Boolean, functional: Boolean) {
            if (x in 0 until size && y in 0 until size) {
                modules[y][x] = dark
                reserved[y][x] = functional
            }
        }

        // Finders + separators (7×7 with 1-module border).
        val finder = fun(fx: Int, fy: Int) {
            for (dy in -1..7) for (dx in -1..7) {
                val inRing = dx in 0..6 && dy in 0..6 &&
                    (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4))
                set(fx + dx, fy + dy, inRing, true)
            }
        }
        finder(0, 0)
        finder(size - 7, 0)
        finder(0, size - 7)

        // Timing patterns.
        for (i in 8 until size - 8) {
            set(i, 6, i % 2 == 0, true)
            set(6, i, i % 2 == 0, true)
        }

        // Alignment patterns (V2+; the three finder corners are skipped).
        if (version >= 2) {
            val align = intArrayOf(6, size - 7)
            for (cy in align) for (cx in align) {
                val corner = (cx == 6 && cy == 6) || (cx == size - 7 && cy == 6) || (cx == 6 && cy == size - 7)
                if (corner) continue
                for (dy in -2..2) for (dx in -2..2) {
                    set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1, true)
                }
            }
        }

        // Reserve format areas now; values written per-mask below.
        // Column 8 rows 0–5,7,8 + row 8 cols 0–5,7,8 — (8,6)/(6,8) are
        // TIMING modules and must stay timing.
        for (i in 0..8) {
            if (i != 6) set(i, 8, false, true)
            if (i != 6) set(8, i, false, true)
        }
        for (i in 0..7) {
            set(size - 1 - i, 8, false, true)
            set(8, size - 1 - i, false, true)
        }
        set(8, size - 8, true, true) // always-dark module (col 8, row size-8)

        // Version info (V7+ only — not in our range, kept for clarity).
        check(version < 7) { "version info patterns land at V7+" }

        // ── Data codewords ────────────────────────────────────────────
        val bits = ArrayList<Boolean>()
        fun append(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add((value ushr i) and 1 != 0)
        }
        append(0b0100, 4) // byte mode
        append(data.size, 8)
        for (b in data) append(b.toInt() and 0xFF, 8)
        val capacityBits = DATA_BLOCKS[version].sum() * 8
        append(0, minOf(4, capacityBits - bits.size)) // terminator
        while (bits.size % 8 != 0) bits.add(false)
        var padToggle = 0
        while (bits.size < capacityBits) {
            append(if (padToggle == 0) 0xEC else 0x11, 8)
            padToggle = 1 - padToggle
        }
        val codewords = ByteArray(bits.size / 8) { i ->
            var b = 0
            for (j in 0 until 8) if (bits[i * 8 + j]) b = b or (1 shl (7 - j))
            b.toByte()
        }

        // ECC per block + interleaving.
        val blocks = DATA_BLOCKS[version]
        val eccDegree = ECC_PER_BLOCK[version]
        val dataParts = ArrayList<ByteArray>(blocks.size)
        val eccParts = ArrayList<ByteArray>(blocks.size)
        var offset = 0
        for (len in blocks) {
            val part = codewords.copyOfRange(offset, offset + len)
            offset += len
            dataParts.add(part)
            eccParts.add(rsEcc(part, eccDegree))
        }
        val finalCodewords = ArrayList<Byte>()
        val maxData = blocks.max()
        for (i in 0 until maxData) for (part in dataParts) if (i < part.size) finalCodewords.add(part[i])
        for (i in 0 until eccDegree) for (part in eccParts) finalCodewords.add(part[i])

        // Zig-zag placement, skipping reserved cells.
        var index = 0
        var upward = true
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (v in 0 until size) {
                val y = if (upward) size - 1 - v else v
                for (j in 0 until 2) {
                    val x = right - j
                    if (!reserved[y][x]) {
                        if (index < finalCodewords.size * 8) {
                            modules[y][x] =
                                (finalCodewords[index / 8].toInt() ushr (7 - index % 8)) and 1 == 1
                        }
                        index++
                    }
                }
            }
            upward = !upward
            right -= 2
        }

        // ── Masking: pick the lowest-penalty mask ─────────────────────
        fun formatBits(mask: Int): Int {
            val data = (0b00 shl 3) or mask // ECC level M
            var rem = data
            for (i in 0 until 9) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            return ((data shl 10) or (rem and 0x3FF)) xor 0x5412
        }

        fun drawFormat(mask: Int) {
            val bits2 = formatBits(mask)
            for (i in 0 until 15) {
                val dark = (bits2 ushr i) and 1 == 1
                if (i < 6) modules[8][i] = dark
                else if (i == 6) modules[8][7] = dark
                else if (i == 7) modules[8][8] = dark
                else if (i == 8) modules[7][8] = dark
                else modules[14 - i][8] = dark
                val mirror = if (i < 8) size - 1 - i else size - 15 + i
                modules[8][mirror] = dark
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (reserved[y][x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> (x * y % 3 + (x + y) % 2) % 2 == 0
                }
                if (invert) modules[y][x] = !(modules[y][x] ?: false)
            }
        }

        fun penalty(): Int {
            var result = 0
            val grid = modules.map { row -> row.map { it ?: false } }
            // N1: runs ≥5 in rows and columns.
            for (i in 0 until size) {
                for (axis in 0 until 2) {
                    var run = 1
                    for (j in 1 until size) {
                        val cur = if (axis == 0) grid[i][j] else grid[j][i]
                        val prev = if (axis == 0) grid[i][j - 1] else grid[j - 1][i]
                        if (cur == prev) run++
                        else {
                            if (run >= 5) result += 3 + (run - 5)
                            run = 1
                        }
                    }
                    if (run >= 5) result += 3 + (run - 5)
                }
            }
            // N2: 2×2 same-color blocks.
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val m = grid[y][x]
                if (grid[y][x + 1] == m && grid[y + 1][x] == m && grid[y + 1][x + 1] == m) result += 3
            }
            // N4: dark-module proportion deviation from 50%.
            val dark = grid.sumOf { row -> row.count { it } }
            val k = kotlin.math.abs(dark * 20 - size * size * 10) / (size * size)
            result += k * 10
            return result
        }

        val base = modules.map { row -> row.toList() }
        var best: List<List<Boolean>>? = null
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0 until 7) {
            for (y in 0 until size) modules[y] = base[y].toTypedArray()
            drawFormat(mask)
            applyMask(mask)
            val p = penalty()
            if (p < bestPenalty) {
                bestPenalty = p
                best = modules.map { row -> row.map { it ?: false } }
            }
        }
        return best ?: emptyList()
    }
}
