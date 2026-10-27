/*
 * Written from the LZO1X stream format as the Linux kernel's
 * Documentation/staging/lzo.rst describes it, not from the LZO library,
 * minilzo or any other implementation's code.
 */
package io.github.cacack.keginker.ezsign

/** Bits in a [lzo1xCompress] hash table index. */
private const val HASH_BITS = 12

/** The shortest match [lzo1xCompress] looks for, and the bytes it hashes. */
private const val MIN_MATCH = 4

/** The furthest back an M2 match reaches. */
private const val M2_MAX_DISTANCE = 2_048

/** The longest M2 match. */
private const val M2_MAX_LENGTH = 8

/** The longest M3 match length (less 2) its first byte holds; longer ones run on. */
private const val M3_MAX_SHORT_LENGTH = 31

/**
 * [block] as an LZO1X stream that a conforming decompressor turns back into
 * it, ending with [END_OF_STREAM].
 *
 * Greedy, in the manner of LZO1X-1: at each byte it looks up the next 4 in
 * a hash table of where 4-byte sequences last appeared, takes the match if
 * there is one and extends it as far as it goes, and otherwise moves on a
 * byte. Matches are coded as M2 (4–8 bytes, as [MIN_MATCH] starts them, up
 * to 2 KiB back) or M3 (any length up to 16 KiB back); [block] is at most
 * [BLOCK_BYTES], so M4 is never needed. Data with nothing to match codes as
 * [lzoLiteralStream] does.
 *
 * Its output has been checked only against the test decoder,
 * `lzo1xDecompress` in SimulatedEzSignTag.kt, written from the same format
 * description; no real display has accepted it yet. [lzoLiteralStream],
 * which test writes have sent to real displays, is the fallback if one
 * doesn't.
 */
internal fun lzo1xCompress(block: ByteArray): ByteArray {
    val out = mutableListOf<Byte>()
    // Where the last match's trailing-literal count goes: the low 2 bits of its
    // second-to-last byte. Valid only right after a match: 1–3 literals that
    // follow it fold into that operand instead of taking an instruction.
    var countAt = -1

    fun lengthBytes(excess: Int) {
        // A 0 byte for each 255, then the remainder (1–255).
        val zeros = (excess - 1) / 255
        repeat(zeros) { out += 0 }
        out += (excess - zeros * 255).toByte()
    }

    // Codes block[from until until] as one literal run. Two runs are never
    // adjacent: a run opens the stream, follows a match, or both. So a run of
    // 1–3 that doesn't open the stream follows a match and can use countAt.
    fun literals(from: Int, until: Int) {
        val n = until - from
        when {
            n == 0 -> return
            out.isEmpty() && n <= 238 -> out += (17 + n).toByte()
            n <= 3 -> out[countAt] = (out[countAt].toInt() or n).toByte()
            n <= 18 -> out += (n - 3).toByte()
            else -> {
                out += 0
                lengthBytes(n - 18)
            }
        }
        for (i in from until until) out += block[i]
    }

    // Codes a match of [length] (at least MIN_MATCH) from [distance] (1 to
    // BLOCK_BYTES) back, then points countAt at its operand for the next run.
    fun match(distance: Int, length: Int) {
        val d = distance - 1
        if (length <= M2_MAX_LENGTH && distance <= M2_MAX_DISTANCE) {
            countAt = out.size
            out += (((length - 1) shl 5) or ((d and 7) shl 2)).toByte()
            out += (d shr 3).toByte()
        } else {
            if (length - 2 <= M3_MAX_SHORT_LENGTH) {
                out += (0x20 or (length - 2)).toByte()
            } else {
                out += 0x20
                lengthBytes(length - 2 - M3_MAX_SHORT_LENGTH)
            }
            countAt = out.size
            out += ((d and 63) shl 2).toByte()
            out += (d shr 6).toByte()
        }
    }

    val n = block.size
    val lastSeen = IntArray(1 shl HASH_BITS) { -1 }
    var literalStart = 0
    var ip = 0
    while (ip + MIN_MATCH <= n) {
        val h = hash4(block, ip)
        val candidate = lastSeen[h]
        lastSeen[h] = ip
        if (candidate < 0 || !(0 until MIN_MATCH).all { block[candidate + it] == block[ip + it] }) {
            ip++
            continue
        }
        var length = MIN_MATCH
        // A match may overlap the bytes it's writing, so a run matches 1 back.
        while (ip + length < n && block[candidate + length] == block[ip + length]) length++
        literals(literalStart, ip)
        match(ip - candidate, length)
        ip += length
        literalStart = ip
    }
    literals(literalStart, n)
    return out.toByteArray() + END_OF_STREAM
}

/** A [HASH_BITS]-bit hash of the 4 bytes of [block] from [at]. */
private fun hash4(block: ByteArray, at: Int): Int {
    val word = (block[at].toInt() and 0xFF) or
        ((block[at + 1].toInt() and 0xFF) shl 8) or
        ((block[at + 2].toInt() and 0xFF) shl 16) or
        ((block[at + 3].toInt() and 0xFF) shl 24)
    return (word * 0x9E3779B1.toInt()) ushr (32 - HASH_BITS)
}
