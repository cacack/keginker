package io.github.cacack.keginker.ezsign

import io.github.cacack.keginker.nfc.Transport
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeMark

// Test-only. Written from alt-core/nfc-eink's protocol.py, image.py and
// card.py, and from the LZO1X format, not from EzSignFrame or the writer, so
// a check against it tests our code against our reading of the tag.

private fun Byte.u() = toInt() and 0xFF

/**
 * Decompresses an LZO1X stream, following the instruction set as
 * lzo1x_decompress reads it: literal runs, the M1–M4 matches with their
 * trailing-literal counts, and the M4 end marker. Throws on a malformed stream.
 */
fun lzo1xDecompress(stream: ByteArray): ByteArray {
    val out = mutableListOf<Byte>()
    var ip = 0
    fun next(): Int = stream[ip++].u()

    /** A length whose base field was 0: a 0 byte for each 255, then a non-zero byte, plus [base]. */
    fun longLength(base: Int): Int {
        var length = 0
        while (stream[ip] == 0.toByte()) {
            length += 255
            ip++
        }
        return length + base + next()
    }
    fun literals(count: Int) = repeat(count) { out += stream[ip++] }
    fun copy(distance: Int, length: Int) {
        require(distance in 1..out.size) { "Match distance $distance with ${out.size} bytes out" }
        // Byte by byte, so a match may overlap what it's writing.
        repeat(length) { out += out[out.size - distance] }
    }

    // The states lzo1x_decompress moves between.
    val loop = 0
    val firstLiteralRun = 1
    val match = 2
    val matchDone = 3

    var t: Int
    var state: Int
    if (stream[0].u() > 17) {
        t = next() - 17
        literals(t)
        if (t < 4) {
            t = next()
            state = match
        } else {
            state = firstLiteralRun
        }
    } else {
        t = 0
        state = loop
    }
    while (true) {
        when (state) {
            loop -> {
                t = next()
                if (t >= 16) {
                    state = match
                } else {
                    literals((if (t == 0) longLength(15) else t) + 3)
                    state = firstLiteralRun
                }
            }
            firstLiteralRun -> {
                t = next()
                if (t >= 16) {
                    state = match
                } else {
                    // M1 straight after a run of 4 or more literals: 3 bytes from 2 KiB further back.
                    copy(1 + 0x0800 + (t shr 2) + (next() shl 2), 3)
                    state = matchDone
                }
            }
            match -> {
                when {
                    t >= 64 -> copy(1 + ((t shr 2) and 7) + (next() shl 3), (t shr 5) + 1)
                    t >= 32 -> {
                        val length = if (t and 31 == 0) longLength(31) else t and 31
                        val low = next()
                        copy(1 + (low shr 2) + (next() shl 6), length + 2)
                    }
                    t >= 16 -> {
                        val length = if (t and 7 == 0) longLength(7) else t and 7
                        val low = next()
                        val distance = ((t and 8) shl 11) + (low shr 2) + (next() shl 6)
                        if (distance == 0) {
                            require(ip == stream.size) { "${stream.size - ip} bytes after the end marker" }
                            return out.toByteArray()
                        }
                        copy(distance + 0x4000, length + 2)
                    }
                    else -> copy(1 + (t shr 2) + (next() shl 2), 2)
                }
                state = matchDone
            }
            matchDone -> {
                // The low 2 bits of the match's second-to-last byte count the literals after it.
                t = stream[ip - 2].u() and 3
                if (t == 0) {
                    state = loop
                } else {
                    literals(t)
                    t = next()
                    state = match
                }
            }
        }
    }
}

private fun reply(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

/**
 * An EZ Sign tag. Needs SELECT of the NDEF application, then `00 20`
 * authentication, before it takes `F0 D3` image fragments. It checks their
 * block and fragment numbering and the last-fragment flag, decompresses
 * each block, and writes it into the panel memory; `F0 D4` refreshes, and
 * `F0 DE` then answers busy [busyPolls] times, or, given [busyMs], until
 * [clock] has advanced that far since the refresh.
 *
 * Each command advances [clock], if given, by [commandMs].
 */
class SimulatedEzSignTag(
    private val info: EzSignDeviceInfo,
    private val busyPolls: Int = 1,
    private val clock: TestTimeSource? = null,
    private val commandMs: Long = 0,
    private val busyMs: Long? = null,
) : Transport {
    private val pixelsPerByte = 8 / info.bitsPerPixel
    private val rowBytes = (info.nativeWidth + pixelsPerByte - 1) / pixelsPerByte
    private val memory = ByteArray(rowBytes * info.nativeHeight)
    private var selected = false
    private var authenticated = false
    private var block = 0
    private var fragment = 0
    private val stream = mutableListOf<Byte>()
    private var busyLeft = 0
    private var refreshedAt: TimeMark? = null

    /** Every command, in order. */
    val sent = mutableListOf<ByteArray>()

    /** The panel memory at the last refresh; null before one. */
    var shown: ByteArray? = null
        private set

    /** Why the tag last answered with an error, for test messages. */
    var lastError: String? = null
        private set

    private val blockCount get() = (memory.size + BLOCK_SIZE - 1) / BLOCK_SIZE

    /** [shown] as colour indices, row-major in the native layout; each row's bytes run from its right-hand end. */
    fun shownPixels(): ByteArray? {
        val memory = shown ?: return null
        val bpp = info.bitsPerPixel
        return ByteArray(info.nativeWidth * info.nativeHeight) { i ->
            val x = i % info.nativeWidth
            val y = i / info.nativeWidth
            val byte = memory[y * rowBytes + rowBytes - 1 - x / pixelsPerByte].u()
            (byte shr (x % pixelsPerByte * bpp) and ((1 shl bpp) - 1)).toByte()
        }
    }

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        clock?.plusAssign(commandMs.milliseconds)
        sent += command
        val c = command.map { it.u() }
        return when {
            c == listOf(0x00, 0xA4, 0x04, 0x00, 0x07, 0xD2, 0x76, 0x00, 0x00, 0x85, 0x01, 0x01, 0x00) -> {
                selected = true
                OK
            }
            c == listOf(0x00, 0x20, 0x00, 0x01, 0x04, 0x20, 0x09, 0x12, 0x10) -> {
                if (!selected) return error("authenticated before SELECT", FILE_NOT_FOUND)
                authenticated = true
                OK
            }
            c.take(2) == listOf(0xF0, 0xD3) -> imageData(c)
            c == listOf(0xF0, 0xD4, 0x85, 0x80, 0x00) -> {
                if (!authenticated) return error("refreshed before authenticating", NOT_AUTHENTICATED)
                if (block != blockCount) return error("refreshed after $block of $blockCount blocks", WRONG_DATA)
                shown = memory.copyOf()
                busyLeft = busyPolls
                refreshedAt = clock?.markNow()
                OK
            }
            c == listOf(0xF0, 0xDE, 0x00, 0x00, 0x01) -> if (isBusy()) reply(0x01, 0x90, 0x00) else reply(0x00, 0x90, 0x00)
            else -> error("unknown command", INS_NOT_SUPPORTED)
        }
    }

    /** Whether the panel is still refreshing, counting this poll against [busyPolls]. */
    private fun isBusy(): Boolean {
        if (busyMs != null) {
            val since = checkNotNull(refreshedAt) { "busyMs needs a clock and a refresh" }
            return since.elapsedNow() < busyMs.milliseconds
        }
        if (busyLeft == 0) return false
        busyLeft--
        return true
    }

    private fun imageData(c: List<Int>): ByteArray {
        if (!authenticated) return error("image data before authenticating", NOT_AUTHENTICATED)
        if (c.size < 7 || c[2] != 0x00 || c[3] !in 0..1 || c[4] != c.size - 5) return error("malformed F0 D3 $c", WRONG_DATA)
        val data = c.drop(7)
        if (data.size !in 1..250) return error("fragment of ${data.size} bytes", WRONG_DATA)
        if (c[5] != block || c[6] != fragment) return error("block ${c[5]} fragment ${c[6]}; expected $block, $fragment", WRONG_DATA)
        if (block >= blockCount) return error("block $block of $blockCount", WRONG_DATA)
        stream += data.map { it.toByte() }
        if (c[3] == 0x00) {
            fragment++
            return OK
        }
        val decoded = try {
            lzo1xDecompress(stream.toByteArray())
        } catch (e: Exception) {
            return error("block $block didn't decompress: $e", WRONG_DATA)
        }
        val start = block * BLOCK_SIZE
        val size = minOf(BLOCK_SIZE, memory.size - start)
        if (decoded.size != size) return error("block $block is ${decoded.size} bytes; expected $size", WRONG_DATA)
        decoded.copyInto(memory, start)
        stream.clear()
        block++
        fragment = 0
        return OK
    }

    private fun error(why: String, status: ByteArray): ByteArray {
        lastError = why
        return status
    }

    private companion object {
        const val BLOCK_SIZE = 2000
        val OK = reply(0x90, 0x00)
        val FILE_NOT_FOUND = reply(0x6A, 0x82)
        val NOT_AUTHENTICATED = reply(0x69, 0x82)
        val WRONG_DATA = reply(0x6A, 0x80)
        val INS_NOT_SUPPORTED = reply(0x6D, 0x00)
    }
}
