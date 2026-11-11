package com.scenix.tv.core

/**
 * A QR Code Model 2 encoder, byte mode, error correction level M, versions 1-20.
 *
 * The phone-pairing sheet needs a QR at couch distance and the app ships no third-party libraries,
 * so this is the whole encoder: segment encoding, Reed-Solomon over GF(256), the function patterns,
 * the zig-zag data placement, all eight masks with the standard penalty score, and format/version
 * information. It is deliberately one-way (encode only, no decoder); [PhonePairing] turns the module
 * grid into a Bitmap.
 *
 * Level M is what the pairing link uses: it survives a dimmed TV screen and a phone camera at an
 * angle without growing the symbol as much as level Q would. Versions above 20 are not needed for
 * a LAN URL and are rejected rather than silently mis-encoded.
 */
class QrCode private constructor(
    val version: Int,
    val size: Int,
    val mask: Int,
    private val modules: BooleanArray,
    private val isFunction: BooleanArray,
) {
    /** True when the module at [x],[y] is dark. */
    fun getModule(x: Int, y: Int): Boolean = modules[y * size + x]

    companion object {
        // Error-correction codewords per block and block count, level M, indexed by version.
        // Version 0 is unused so the arrays line up with the QR version number.
        private val ECC_PER_BLOCK = intArrayOf(
            -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24,
            28, 28, 26, 26, 26,
        )
        private val ECC_BLOCKS = intArrayOf(
            -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10,
            10, 11, 13, 14, 16,
        )

        // Finder-like run detection: a 1:1:3:1:1 ratio surrounded by at least four light modules.
        private const val PENALTY_N1 = 3
        private const val PENALTY_N2 = 3
        private const val PENALTY_N3 = 40
        private const val PENALTY_N4 = 10

        /** Alignment-pattern centre coordinates by version; version 1 has none. */
        private val ALIGNMENT_POSITIONS = arrayOf(
            intArrayOf(),
            intArrayOf(),
            intArrayOf(6, 18),
            intArrayOf(6, 22),
            intArrayOf(6, 26),
            intArrayOf(6, 30),
            intArrayOf(6, 34),
            intArrayOf(6, 22, 38),
            intArrayOf(6, 24, 42),
            intArrayOf(6, 26, 46),
            intArrayOf(6, 28, 50),
            intArrayOf(6, 30, 54),
            intArrayOf(6, 32, 58),
            intArrayOf(6, 34, 62),
            intArrayOf(6, 26, 46, 66),
            intArrayOf(6, 26, 48, 70),
            intArrayOf(6, 26, 50, 74),
            intArrayOf(6, 30, 54, 78),
            intArrayOf(6, 30, 56, 82),
            intArrayOf(6, 30, 58, 86),
            intArrayOf(6, 34, 62, 90),
        )

        /**
         * Encode [text] as UTF-8 bytes in the smallest version that fits, choosing the mask with
         * the lowest penalty. Returns null when the text needs more than version 20.
         */
        fun encode(text: String, minVersion: Int = 1, maxVersion: Int = 20): QrCode? {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val first = minVersion.coerceIn(1, 20)
            val last = maxVersion.coerceIn(first, 20)

            var version = first
            while (version <= last) {
                val capacityBits = numDataCodewords(version) * 8
                val neededBits = 4 + charCountBits(version) + bytes.size * 8
                if (neededBits <= capacityBits) break
                version++
            }
            if (version > last) return null

            val data = makeDataCodewords(bytes, version)
            val codewords = addEccAndInterleave(data, version)

            var best: QrCode? = null
            var bestPenalty = Int.MAX_VALUE
            for (candidateMask in 0..7) {
                val qr = QrCode(
                    version = version,
                    size = version * 4 + 17,
                    mask = candidateMask,
                    modules = BooleanArray((version * 4 + 17) * (version * 4 + 17)),
                    isFunction = BooleanArray((version * 4 + 17) * (version * 4 + 17)),
                )
                qr.drawFunctionPatterns()
                qr.drawCodewords(codewords)
                qr.applyMask(candidateMask)
                qr.drawFormatBits(candidateMask)
                val penalty = qr.penaltyScore()
                if (penalty < bestPenalty) {
                    best = qr
                    bestPenalty = penalty
                }
            }
            return best
        }

        private fun charCountBits(version: Int): Int = if (version <= 9) 8 else 16

        /** Modules available for data and error correction, excluding function patterns. */
        private fun numRawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        private fun numDataCodewords(version: Int): Int =
            numRawDataModules(version) / 8 - ECC_PER_BLOCK[version] * ECC_BLOCKS[version]

        /** Byte-mode segment, terminator, byte alignment and the standard 0xEC/0x11 padding. */
        private fun makeDataCodewords(bytes: ByteArray, version: Int): ByteArray {
            val bits = BitBuffer()
            bits.append(0b0100, 4)
            bits.append(bytes.size, charCountBits(version))
            for (b in bytes) bits.append(b.toInt() and 0xFF, 8)

            val capacity = numDataCodewords(version) * 8
            bits.append(0, minOf(4, capacity - bits.size))
            bits.append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) {
                bits.append(pad, 8)
                pad = pad xor 0xFD // 0xEC -> 0x11 -> 0xEC
            }
            check(bits.size == capacity) { "QR data segment overflowed its version" }
            return bits.toByteArray()
        }

        /**
         * Split the data codewords into blocks, append Reed-Solomon parity to each, then interleave
         * both the data and parity columns. A version can have one longer block; the interleave
         * skips the missing byte rather than shifting every following block.
         */
        private fun addEccAndInterleave(data: ByteArray, version: Int): ByteArray {
            val numBlocks = ECC_BLOCKS[version]
            val blockEccLen = ECC_PER_BLOCK[version]
            val rawCodewords = numRawDataModules(version) / 8
            val numShortBlocks = numBlocks - rawCodewords % numBlocks
            val shortBlockLen = rawCodewords / numBlocks
            val divisor = reedSolomonDivisor(blockEccLen)

            // Every block gets the same total length: short blocks carry one zero pad byte before
            // their parity, so the parity columns line up when interleaved.
            val blocks = ArrayList<ByteArray>(numBlocks)
            var offset = 0
            for (i in 0 until numBlocks) {
                val dataLen = shortBlockLen - blockEccLen + if (i < numShortBlocks) 0 else 1
                val dat = data.copyOfRange(offset, offset + dataLen)
                offset += dataLen
                val ecc = reedSolomonRemainder(dat, divisor)
                val block = ByteArray(shortBlockLen + 1)
                dat.copyInto(block, 0)
                ecc.copyInto(block, block.size - blockEccLen)
                blocks.add(block)
            }

            val result = ByteArray(rawCodewords)
            var k = 0
            for (i in 0 until blocks[0].size) {
                for (j in blocks.indices) {
                    // The pad byte in a short block is not a codeword.
                    if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                        result[k++] = blocks[j][i]
                    }
                }
            }
            check(k == result.size) { "QR interleave left ${result.size - k} codewords" }
            return result
        }

        /** Generator polynomial for [degree] parity bytes, highest term omitted. */
        private fun reedSolomonDivisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in result.indices) {
                    result[j] = gfMultiply(result[j], root)
                    if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
                }
                root = gfMultiply(root, 0x02)
            }
            return result
        }

        private fun reedSolomonRemainder(data: ByteArray, divisor: IntArray): ByteArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor result[0]
                for (i in 0 until result.size - 1) result[i] = result[i + 1]
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor gfMultiply(divisor[i], factor)
            }
            return ByteArray(result.size) { result[it].toByte() }
        }

        /** Multiplication in GF(256) with the QR primitive polynomial x^8 + x^4 + x^3 + x^2 + 1. */
        private fun gfMultiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z and 0xFF
        }
    }

    private fun setFunction(x: Int, y: Int, dark: Boolean) {
        modules[y * size + x] = dark
        isFunction[y * size + x] = true
    }

    private fun drawFunctionPatterns() {
        // Timing patterns run through the middle of the symbol.
        for (i in 0 until size) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }

        // Three finder patterns; the fourth corner is data, which is how a scanner knows orientation.
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)

        val positions = ALIGNMENT_POSITIONS[version]
        for (i in positions.indices) {
            for (j in positions.indices) {
                val onFinder = (i == 0 && j == 0) ||
                    (i == 0 && j == positions.size - 1) ||
                    (i == positions.size - 1 && j == 0)
                if (!onFinder) drawAlignment(positions[i], positions[j])
            }
        }

        // Reserve the format area with a placeholder mask; drawFormatBits overwrites it once the
        // mask is chosen. Version information is fixed per version and can be drawn now.
        drawFormatBits(0)
        drawVersionBits()
    }

    private fun drawFinder(cx: Int, cy: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until size && y in 0 until size) {
                    val dist = maxOf(if (dx < 0) -dx else dx, if (dy < 0) -dy else dy)
                    setFunction(x, y, dist != 2 && dist != 4)
                }
            }
        }
    }

    private fun drawAlignment(cx: Int, cy: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                val dist = maxOf(if (dx < 0) -dx else dx, if (dy < 0) -dy else dy)
                setFunction(cx + dx, cy + dy, dist != 1)
            }
        }
    }

    private fun drawFormatBits(mask: Int) {
        // Level M format bits are 0b00. BCH(15,5) with generator 0x537, then the 0x5412 mask.
        val data = (0b00 shl 3) or mask
        var rem = data
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        val bits = ((data shl 10) or rem) xor 0x5412

        for (i in 0..5) setFunction(8, i, bit(bits, i))
        setFunction(8, 7, bit(bits, 6))
        setFunction(8, 8, bit(bits, 7))
        setFunction(7, 8, bit(bits, 8))
        for (i in 9 until 15) setFunction(14 - i, 8, bit(bits, i))

        for (i in 0 until 8) setFunction(size - 1 - i, 8, bit(bits, i))
        for (i in 8 until 15) setFunction(8, size - 15 + i, bit(bits, i))
        setFunction(8, size - 8, true) // Always-dark module.
    }

    private fun drawVersionBits() {
        if (version < 7) return
        var rem = version
        for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
        val bits = (version shl 12) or rem
        for (i in 0 until 18) {
            val dark = bit(bits, i)
            val a = size - 11 + i % 3
            val b = i / 3
            setFunction(a, b, dark)
            setFunction(b, a, dark)
        }
    }

    /** Two-module-wide zig-zag from the bottom-right, skipping the vertical timing column. */
    private fun drawCodewords(data: ByteArray) {
        var bitIndex = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                val upward = ((right + 1) and 2) == 0
                val y = if (upward) size - 1 - vert else vert
                for (j in 0 until 2) {
                    val x = right - j
                    if (!isFunction[y * size + x] && bitIndex < data.size * 8) {
                        val value = (data[bitIndex ushr 3].toInt() ushr (7 - (bitIndex and 7))) and 1
                        modules[y * size + x] = value != 0
                        bitIndex++
                    }
                }
            }
            right -= 2
        }
        check(bitIndex == data.size * 8) { "QR data was not fully placed" }
    }

    private fun applyMask(mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    7 -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    else -> false
                }
                if (invert && !isFunction[y * size + x]) {
                    modules[y * size + x] = !modules[y * size + x]
                }
            }
        }
    }

    /** The four penalty rules from ISO/IEC 18004; the lowest total picks the mask. */
    private fun penaltyScore(): Int {
        var result = 0

        for (y in 0 until size) {
            var runColor = false
            var runLength = 0
            val history = IntArray(7)
            for (x in 0 until size) {
                if (modules[y * size + x] == runColor) {
                    runLength++
                    if (runLength == 5) result += PENALTY_N1 else if (runLength > 5) result++
                } else {
                    addRunHistory(runLength, history)
                    if (!runColor) result += countFinderRuns(history) * PENALTY_N3
                    runColor = modules[y * size + x]
                    runLength = 1
                }
            }
            result += finishRuns(runColor, runLength, history) * PENALTY_N3
        }

        for (x in 0 until size) {
            var runColor = false
            var runLength = 0
            val history = IntArray(7)
            for (y in 0 until size) {
                if (modules[y * size + x] == runColor) {
                    runLength++
                    if (runLength == 5) result += PENALTY_N1 else if (runLength > 5) result++
                } else {
                    addRunHistory(runLength, history)
                    if (!runColor) result += countFinderRuns(history) * PENALTY_N3
                    runColor = modules[y * size + x]
                    runLength = 1
                }
            }
            result += finishRuns(runColor, runLength, history) * PENALTY_N3
        }

        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = modules[y * size + x]
                if (c == modules[y * size + x + 1] &&
                    c == modules[(y + 1) * size + x] &&
                    c == modules[(y + 1) * size + x + 1]
                ) {
                    result += PENALTY_N2
                }
            }
        }

        var dark = 0
        for (module in modules) if (module) dark++
        val total = size * size
        val deviation = kotlin.math.abs(dark * 20 - total * 10)
        val k = ((deviation + total - 1) / total - 1).coerceAtLeast(0)
        result += k * PENALTY_N4
        return result
    }

    private fun addRunHistory(runLength: Int, history: IntArray) {
        // Treat the area outside the symbol as light so a finder at the edge is still detected.
        val length = runLength + if (history[0] == 0) size else 0
        history.copyInto(history, 1, 0, history.size - 1)
        history[0] = length
    }

    private fun finishRuns(runColor: Boolean, runLength: Int, history: IntArray): Int {
        var length = runLength
        if (runColor) {
            addRunHistory(length, history)
            length = 0
        }
        addRunHistory(length + size, history)
        return countFinderRuns(history)
    }

    private fun countFinderRuns(history: IntArray): Int {
        val n = history[1]
        val core = n > 0 && history[2] == n && history[3] == n * 3 && history[4] == n && history[5] == n
        if (!core) return 0
        return (if (history[0] >= n * 4 && history[6] >= n) 1 else 0) +
            (if (history[6] >= n * 4 && history[0] >= n) 1 else 0)
    }

    private fun bit(value: Int, index: Int): Boolean = ((value ushr index) and 1) != 0

    private class BitBuffer {
        private val values = ArrayList<Boolean>(256)

        val size: Int get() = values.size

        fun append(value: Int, length: Int) {
            for (i in length - 1 downTo 0) values.add(((value ushr i) and 1) != 0)
        }

        fun toByteArray(): ByteArray {
            val out = ByteArray((values.size + 7) / 8)
            for (i in values.indices) {
                if (values[i]) out[i ushr 3] = (out[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
            }
            return out
        }
    }
}
