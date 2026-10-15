package io.github.cacack.tagpaper.nfc

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

/** Copies [bytes] into [NSData] in one step, for Swift callers. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun nsData(bytes: ByteArray): NSData =
    if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
