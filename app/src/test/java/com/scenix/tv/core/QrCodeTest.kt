package com.scenix.tv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Offline coverage for the in-house QR encoder.
 *
 * The expected 21x21 symbol below was decoded successfully by CoreImage and Vision before it was
 * pinned here, so a change in any stage (segment bits, Reed-Solomon, placement, mask) fails loudly.
 */
class QrCodeTest {

    @Test
    fun helloMatchesTheValidatedSymbol() {
        val qr = QrCode.encode("HELLO")
        assertNotNull(qr)
        checkNotNull(qr)
        assertEquals(1, qr.version)
        assertEquals(21, qr.size)
        assertEquals(
            listOf(
                "#######.##.#..#######",
                "#.....#..##.#.#.....#",
                "#.###.#..####.#.###.#",
                "#.###.#.#..#..#.###.#",
                "#.###.#.#...#.#.###.#",
                "#.....#.#.##..#.....#",
                "#######.#.#.#.#######",
                "........#####........",
                "#...#.######.#####..#",
                "...###..#.###..#.####",
                "#.##..#.#.##..###..#.",
                "###..#...#...##.#....",
                "..#.###..#..###...##.",
                "........###.###..#.##",
                "#######.##..##...#.#.",
                "#.....#....##..#...#.",
                "#.###.#.#..#..###.#.#",
                "#.###.#....##....#.##",
                "#.###.#..###..####...",
                "#.....#..#...##......",
                "#######.#...#####.#.#",
            ),
            (0 until qr.size).map { y ->
                (0 until qr.size).map { x -> if (qr.getModule(x, y)) '#' else '.' }.joinToString("")
            },
        )
    }

    @Test
    fun versionsGrowWithThePayload() {
        assertEquals(1, QrCode.encode("HELLO")!!.version)
        // 26 bytes no longer fit version 1 (16 data codewords at level M).
        assertEquals(2, QrCode.encode("http://hub:8788/#/settings")!!.version)
        assertEquals(2, QrCode.encode("x".repeat(26))!!.version)
        assertEquals(3, QrCode.encode("x".repeat(40))!!.version)
        assertEquals(5, QrCode.encode("x".repeat(80))!!.version)
        assertEquals(8, QrCode.encode("x".repeat(150))!!.version)
    }

    @Test
    fun oversizedPayloadIsRejectedRatherThanTruncated() {
        assertNull(QrCode.encode("x".repeat(700), maxVersion = 20))
    }

    @Test
    fun everyEncodedSymbolCarriesLevelMFormatBits() {
        val qr = QrCode.encode("http://192.168.0.24:8788/remote?t=0123456789abcdef&c=7K2M9Q")!!
        // Both format copies, read LSB-first the way drawFormatBits writes them.
        val first = (0..5).map { qr.getModule(8, it) } +
            listOf(qr.getModule(8, 7), qr.getModule(8, 8), qr.getModule(7, 8)) +
            (9..14).map { qr.getModule(14 - it, 8) }
        val second = (0 until 8).map { qr.getModule(qr.size - 1 - it, 8) } +
            (8..14).map { qr.getModule(8, qr.size - 15 + it) }
        assertEquals(first, second)

        var bits = 0
        first.forEachIndexed { i, dark -> if (dark) bits = bits or (1 shl i) }
        val data = (bits xor 0x5412) ushr 10
        assertEquals("format level must be M (00)", 0, data ushr 3)
    }
}
