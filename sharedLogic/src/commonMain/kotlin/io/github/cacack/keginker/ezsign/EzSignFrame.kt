/*
 * The packing, block split and fragmenting follow alt-core/nfc-eink's
 * src/nfc_eink/image.py (pack_row, split_blocks, make_fragments;
 * https://github.com/alt-core/nfc-eink, commit 682519f), licensed under the
 * MIT License.
 *
 * Modified: ported from Python to Kotlin; frames are packed in the panel's
 * native layout, with no rotation or flips; each block is stored as one LZO1X
 * literal run instead of being compressed with LZO1X-1.
 */
package io.github.cacack.keginker.ezsign

/** The most packed bytes in one image block. */
internal const val BLOCK_BYTES = 2_000

/** The most compressed bytes in one `F0 D3` command, after its block and fragment numbers. */
internal const val FRAGMENT_BYTES = 250

/**
 * Packs [pixels] (colour indices, row-major in the native layout of [info],
 * [EzSignDeviceInfo.nativeWidth] wide) at [EzSignDeviceInfo.bitsPerPixel].
 *
 * Within a row the bytes run from the right-hand end: the row's first byte
 * holds its last pixels. Each byte holds its pixels lowest bits first, so
 * at 2 bits per pixel it's `p0 | p1 << 2 | p2 << 4 | p3 << 6`. A row whose
 * width isn't a whole number of bytes is padded with index 0.
 */
internal fun packFrame(pixels: ByteArray, info: EzSignDeviceInfo): ByteArray {
    val width = info.nativeWidth
    val height = info.nativeHeight
    require(pixels.size == width * height) { "Frame is ${pixels.size} pixels; the panel needs $width × $height" }
    val bpp = info.bitsPerPixel
    val pixelsPerByte = 8 / bpp
    val rowBytes = (width + pixelsPerByte - 1) / pixelsPerByte
    val packed = ByteArray(rowBytes * height)
    for (y in 0 until height) {
        for (byte in 0 until rowBytes) {
            val first = (rowBytes - 1 - byte) * pixelsPerByte
            var value = 0
            for (i in 0 until pixelsPerByte) {
                val x = first + i
                if (x < width) value = value or ((pixels[y * width + x].toInt() and 0xFF) shl (i * bpp))
            }
            packed[y * rowBytes + byte] = value.toByte()
        }
    }
    return packed
}

/** [frame] in blocks of [BLOCK_BYTES]; the last may be shorter. */
internal fun frameBlocks(frame: ByteArray): List<ByteArray> =
    (frame.indices step BLOCK_BYTES).map { frame.copyOfRange(it, minOf(it + BLOCK_BYTES, frame.size)) }

/**
 * [block] as an LZO1X stream that a conforming decompressor turns back into
 * it: one literal run, then the end-of-stream marker `11 00 00`. It isn't
 * compressed, which costs only fragments: a test write needs no better.
 *
 * A first run of n literals is coded as one byte 17 + n when n ≤ 238;
 * otherwise as `00`, a `00` for each 255 in n − 18 beyond the last, then
 * the remainder (1–255). An empty block is the end marker alone.
 */
internal fun lzoLiteralStream(block: ByteArray): ByteArray {
    val n = block.size
    val header = when {
        n == 0 -> ByteArray(0)
        n <= 238 -> byteArrayOf((17 + n).toByte())
        else -> {
            val zeros = (n - 18 - 1) / 255
            ByteArray(1 + zeros) + byteArrayOf((n - 18 - zeros * 255).toByte())
        }
    }
    return header + block + END_OF_STREAM
}

/** The LZO1X end-of-stream marker: a distance-0 long match. */
private val END_OF_STREAM = byteArrayOf(0x11, 0x00, 0x00)

/** [stream] in pieces of at most [FRAGMENT_BYTES]. */
internal fun fragments(stream: ByteArray): List<ByteArray> =
    (stream.indices step FRAGMENT_BYTES).map { stream.copyOfRange(it, minOf(it + FRAGMENT_BYTES, stream.size)) }
