package io.github.cacack.keginker.ezsign

import io.github.cacack.keginker.image.forEachSeed
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

/** Asserts [block] survives [lzo1xCompress], and returns the compressed stream. */
private fun roundTrip(block: ByteArray): ByteArray {
    val stream = lzo1xCompress(block)
    assertContentEquals(block, lzo1xDecompress(stream), "${block.size} bytes")
    return stream
}

/** A block of up to [BLOCK_BYTES] mixing random bytes, runs of one byte, and copies of what came before. */
private fun Random.nextMixedBlock(): ByteArray {
    val size = nextInt(0, BLOCK_BYTES + 1)
    val block = ByteArray(size)
    var at = 0
    while (at < size) {
        val length = minOf(size - at, nextInt(1, 300))
        when (nextInt(3)) {
            0 -> nextBytes(block, at, at + length)
            1 -> block.fill(nextInt(256).toByte(), at, at + length)
            else -> if (at > 0) {
                val from = nextInt(at)
                for (i in 0 until length) block[at + i] = block[from + i]
            }
        }
        at += length
    }
    return block
}

class Lzo1xTest {
    @Test
    fun matchesAreCodedAsTheFormatCodesThem() {
        // The streams theReferenceDecompressorFollowsMatches builds by hand.
        // 4 literals, an M2 match of 4 from 4 back, the end marker.
        assertContentEquals(
            bytes(0x15, 0x61, 0x62, 0x63, 0x64, 0x6C, 0x00, 0x11, 0x00, 0x00),
            lzo1xCompress("abcdabcd".encodeToByteArray()),
        )
        // The same match with 2 trailing literals in its low bits.
        assertContentEquals(
            bytes(0x15, 0x61, 0x62, 0x63, 0x64, 0x6E, 0x00, 0x78, 0x79, 0x11, 0x00, 0x00),
            lzo1xCompress("abcdabcdxy".encodeToByteArray()),
        )
    }

    @Test
    fun blocksTooShortToMatchAreLiteralsOnly() {
        assertContentEquals(bytes(0x11, 0x00, 0x00), roundTrip(ByteArray(0)))
        for (size in 1..3) {
            val block = ByteArray(size) { 0x55 }
            assertContentEquals(lzoLiteralStream(block), roundTrip(block))
        }
    }

    @Test
    fun aFirstLiteralRunLongerThan238BytesSurvives() {
        val head = Random(1).nextBytes(300)

        roundTrip(head + head + ByteArray(50))
    }

    @Test
    fun runsOfOneByteShrinkToAFewBytes() {
        assertTrue(roundTrip(ByteArray(BLOCK_BYTES)).size < 20)
        // All white at 2 bits per pixel: index 1 in every pixel.
        assertTrue(roundTrip(ByteArray(BLOCK_BYTES) { 0x55 }).size < 20)
    }

    @Test
    fun aLabelLikeBlockShrinksFarBelowItsSize() {
        // Mostly white, with black runs like text strokes.
        val random = Random(7)
        val block = ByteArray(BLOCK_BYTES) { 0x55 }
        repeat(40) {
            val at = random.nextInt(BLOCK_BYTES - 20)
            block.fill(0x00, at, at + random.nextInt(2, 20))
        }

        assertTrue(roundTrip(block).size < BLOCK_BYTES / 4)
    }

    @Test
    fun incompressibleBlocksStillSurvive() = forEachSeed { random ->
        roundTrip(random.nextBytes(random.nextInt(0, BLOCK_BYTES + 1)))
    }

    @Test
    fun mixedBlocksSurvive() = forEachSeed { random ->
        roundTrip(random.nextMixedBlock())
    }

    @Test
    fun aLabelOnThe42InchPanelNeedsFarFewerFragments() {
        // White, with a black border and rows of black bars standing in for text.
        val info = PANEL_42
        val width = info.nativeWidth
        val height = info.nativeHeight
        val pixels = ByteArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val border = x < 8 || y < 8 || x >= width - 8 || y >= height - 8
            val text = y % 40 in 16..31 && x in 24 until width - 24 && (x / 6) % 3 != 0
            if (border || text) 0 else 1
        }
        val blocks = frameBlocks(packFrame(pixels, info))

        val literal = blocks.sumOf { fragments(lzoLiteralStream(it)).size }
        val compressed = blocks.sumOf { fragments(roundTrip(it)).size }

        assertTrue(compressed * 4 < literal, "$compressed fragments compressed, $literal literal")
    }
}
