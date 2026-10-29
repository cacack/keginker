package io.github.cacack.keginker.ezsign

import io.github.cacack.keginker.image.forEachSeed
import io.github.cacack.keginker.probe.EZ_SIGN_29_DEVICE_INFO
import io.github.cacack.keginker.probe.EZ_SIGN_42_DEVICE_INFO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

/** A decoded device-information reply's info, from the reply's data. */
internal fun infoFrom(data: ByteArray): EzSignDeviceInfo =
    (parseEzSignDeviceInfo(data + bytes(0x90, 0x00)) as EzSignDeviceInfoParse.Decoded).info

/** A made-up panel's info: an A0 TLV giving [width] × [height] at [colorMode]. */
internal fun panelInfo(width: Int, height: Int, colorMode: Int): EzSignDeviceInfo {
    val bpp = if (colorMode == 0x07) 2 else 1
    val raw = height * bpp
    return infoFrom(bytes(0xA0, 0x07, 0xF0, colorMode, 0x20, raw shr 8, raw and 0xFF, width shr 8, width and 0xFF))
}

internal val PANEL_29 get() = infoFrom(EZ_SIGN_29_DEVICE_INFO)
internal val PANEL_42 get() = infoFrom(EZ_SIGN_42_DEVICE_INFO)

/** 1 bit per pixel, with rows that aren't a whole number of bytes. */
internal val PANEL_MONO_122 get() = panelInfo(width = 122, height = 250, colorMode = 0x01)

class EzSignFrameTest {
    @Test
    fun aRowsFirstByteHoldsItsLastPixelsLowestBitsFirst() {
        val info = panelInfo(width = 8, height = 1, colorMode = 0x07)

        val packed = packFrame(bytes(0, 1, 2, 3, 3, 2, 1, 0), info)

        // Pixels 4–7 (3 2 1 0) then 0–3 (0 1 2 3), each p0 | p1 << 2 | p2 << 4 | p3 << 6.
        assertContentEquals(bytes(0b00_01_10_11, 0b11_10_01_00), packed)
    }

    @Test
    fun oneBitRowsArePaddedWithIndex0AtTheirRightHandEnd() {
        val info = panelInfo(width = 10, height = 1, colorMode = 0x01)

        val packed = packFrame(ByteArray(10) { 1 }, info)

        // The first byte holds pixels 8–15, of which only 8 and 9 exist.
        assertContentEquals(bytes(0b0000_0011, 0xFF), packed)
    }

    @Test
    fun framesSplitInto2000ByteBlocksWithAShorterLast() {
        val frame = packFrame(ByteArray(128 * 296), PANEL_29)

        assertEquals(32 * 296, frame.size)
        assertEquals(listOf(2000, 2000, 2000, 2000, 1472), frameBlocks(frame).map { it.size })
        assertEquals(List(15) { 2000 }, frameBlocks(packFrame(ByteArray(400 * 300), PANEL_42)).map { it.size })
    }

    @Test
    fun literalRunLengthsAreCodedAsLzo1xCodesThem() {
        // Worked from the LZO1X format: 17 + n up to 238; then 00, a 00 per 255, and n − 18's remainder.
        assertContentEquals(bytes(0x11, 0x00, 0x00), lzoLiteralStream(ByteArray(0)))
        assertContentEquals(bytes(0x12), lzoLiteralStream(ByteArray(1)).copyOfRange(0, 1))
        assertContentEquals(bytes(0xFF), lzoLiteralStream(ByteArray(238)).copyOfRange(0, 1))
        assertContentEquals(bytes(0x00, 221), lzoLiteralStream(ByteArray(239)).copyOfRange(0, 2))
        assertContentEquals(bytes(0x00, 0xFF), lzoLiteralStream(ByteArray(273)).copyOfRange(0, 2))
        assertContentEquals(bytes(0x00, 0x00, 0x01), lzoLiteralStream(ByteArray(274)).copyOfRange(0, 3))
        val full = lzoLiteralStream(ByteArray(2000) { 7 })
        assertContentEquals(bytes(0x00, 0, 0, 0, 0, 0, 0, 0, 197), full.copyOfRange(0, 9))
        assertEquals(9 + 2000 + 3, full.size)
        assertContentEquals(bytes(0x11, 0x00, 0x00), full.copyOfRange(full.size - 3, full.size))
    }

    @Test
    fun theReferenceDecompressorFollowsMatches() {
        // Hand-built from the LZO1X format, with matches no literal stream holds.
        // 4 literals, an M2 match of 4 from 4 back, the end marker.
        assertContentEquals("abcdabcd".encodeToByteArray(), lzo1xDecompress(bytes(0x15, 0x61, 0x62, 0x63, 0x64, 0x6C, 0x00, 0x11, 0x00, 0x00)))
        // The same match with 2 trailing literals in its low bits.
        assertContentEquals(
            "abcdabcdxy".encodeToByteArray(),
            lzo1xDecompress(bytes(0x15, 0x61, 0x62, 0x63, 0x64, 0x6E, 0x00, 0x78, 0x79, 0x11, 0x00, 0x00)),
        )
        // 1 literal, then an overlapping M3 match of 7 from 1 back.
        assertContentEquals("aaaaaaaa".encodeToByteArray(), lzo1xDecompress(bytes(0x12, 0x61, 0x25, 0x00, 0x00, 0x11, 0x00, 0x00)))
        // A match reaching before the start is malformed.
        assertFailsWith<IllegalArgumentException> { lzo1xDecompress(bytes(0x12, 0x61, 0x25, 0x04, 0x00, 0x11, 0x00, 0x00)) }
    }

    @Test
    fun randomBlocksSurviveTheLiteralStream() = forEachSeed { random ->
        val block = random.nextBytes(random.nextInt(0, BLOCK_BYTES + 1))

        assertContentEquals(block, lzo1xDecompress(lzoLiteralStream(block)), "${block.size} bytes")
    }

    @Test
    fun fragmentsAreAtMost250BytesAndRejoinTheStream() = forEachSeed { random ->
        val stream = random.nextBytes(random.nextInt(1, 2 * BLOCK_BYTES))

        val pieces = fragments(stream)

        assertTrue(pieces.all { it.size in 1..FRAGMENT_BYTES })
        assertTrue(pieces.dropLast(1).all { it.size == FRAGMENT_BYTES })
        assertContentEquals(stream, pieces.reduce { a, b -> a + b })
    }
}
