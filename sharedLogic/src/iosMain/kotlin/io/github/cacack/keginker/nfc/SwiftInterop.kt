package io.github.cacack.keginker.nfc

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/** Copies [data] into a Kotlin [ByteArray] in one step, for Swift callers. */
@OptIn(ExperimentalForeignApi::class)
fun kotlinBytes(data: NSData): ByteArray {
    val bytes = ByteArray(data.length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return bytes
}

/** Copies [data], native-endian 32-bit integers, into a Kotlin [IntArray] in one step, for Swift callers. */
@OptIn(ExperimentalForeignApi::class)
fun kotlinInts(data: NSData): IntArray {
    require(data.length % Int.SIZE_BYTES.toULong() == 0UL) { "${data.length} bytes isn't a whole number of Ints" }
    val ints = IntArray((data.length / Int.SIZE_BYTES.toULong()).toInt())
    if (ints.isNotEmpty()) ints.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return ints
}

/** Copies [bytes] into [NSData] in one step, for Swift callers. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun nsData(bytes: ByteArray): NSData =
    if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
