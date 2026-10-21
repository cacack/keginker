package io.github.cacack.keginker.probe

import io.github.cacack.keginker.nfc.Transport

// Tags simulated from the NFC Forum Type 2 and Type 4 specs, for the probe
// tests. Each one fails the test if it's sent anything but a read.

/** Whether [command] is one of the reads in ProbeCommands.kt. */
fun isReadOnly(command: ByteArray): Boolean {
    val c = command.map { it.toInt() and 0xFF }
    return when {
        c.size == 2 && c[0] == 0x30 -> true
        c.size == 5 && c[0] == 0x00 && c[1] == 0xB0 -> true
        c.size >= 5 && c[0] == 0x00 && c[1] == 0xA4 && c[2] == 0x04 && c[3] == 0x00 && c.size == 6 + c[4] -> true
        c.size == 7 && c[0] == 0x00 && c[1] == 0xA4 && c[2] == 0x00 && c[3] == 0x0C && c[4] == 0x02 -> true
        else -> false
    }
}

/** Records what it's sent, and closes once. */
abstract class ProbeTag : Transport {
    val sent = mutableListOf<ByteArray>()
    var closes = 0
        private set

    final override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        // An Error, not an Exception, so the probe can't catch it as a lost tag.
        if (!isReadOnly(command)) throw AssertionError("The probe sent a command that isn't a read: ${command.toList()}")
        sent += command.copyOf()
        return reply(command)
    }

    protected abstract fun reply(command: ByteArray): ByteArray

    override fun close() {
        closes++
    }
}

private val OK = bytes(0x90, 0x00)
private val FILE_NOT_FOUND = bytes(0x6A, 0x82)
private val WRONG_PARAMETERS = bytes(0x6B, 0x00)

/**
 * A Type 4 tag with the NDEF application, a capability container allowing
 * reads of [maxRead] bytes, and an NDEF file holding [message].
 */
class Type4Tag(message: ByteArray, private val maxRead: Int = 0xFF, private val hasNdefApp: Boolean = true) : ProbeTag() {
    private val ndefFile = bytes(message.size shr 8, message.size and 0xFF) + message
    private val cc = bytes(
        0x00, 0x0F, 0x20, maxRead shr 8, maxRead and 0xFF, 0x00, 0xFF,
        0x04, 0x06, 0xE1, 0x04, 0x10, 0x00, 0x00, 0xFF,
    )
    private var applicationSelected = false
    private var selected: ByteArray? = null

    override fun reply(command: ByteArray): ByteArray {
        val c = command.map { it.toInt() and 0xFF }
        return when {
            c[1] == 0xA4 && c[2] == 0x04 -> {
                applicationSelected = hasNdefApp && command.copyOfRange(5, 5 + c[4]).contentEquals(NDEF_APPLICATION_AID)
                selected = null
                if (applicationSelected) OK else FILE_NOT_FOUND
            }
            c[1] == 0xA4 -> {
                val id = c[5] shl 8 or c[6]
                selected = when {
                    !applicationSelected -> null
                    id == 0xE103 -> cc
                    id == 0xE104 -> ndefFile
                    else -> null
                }
                if (selected != null) OK else FILE_NOT_FOUND
            }
            else -> {
                val file = selected ?: return FILE_NOT_FOUND
                val offset = c[2] shl 8 or c[3]
                val length = c[4]
                if (length > maxRead || offset >= file.size) return WRONG_PARAMETERS
                file.copyOfRange(offset, minOf(file.size, offset + length)) + OK
            }
        }
    }
}

/** A Type 2 tag whose memory from page 0 is [memory]; a read past the end gets a NAK. */
class Type2Tag(private val memory: ByteArray) : ProbeTag() {
    override fun reply(command: ByteArray): ByteArray {
        val start = (command[1].toInt() and 0xFF) * 4
        if (start >= memory.size) return bytes(0x00)
        // A read wraps around to page 0 past the last page.
        return ByteArray(16) { memory[(start + it) % memory.size] }
    }
}

/** Type 2 memory: a UID and lock bytes in pages 0–2, the capability container in page 3, then [data]. */
fun type2Memory(data: ByteArray, pages: Int = 64): ByteArray {
    val memory = ByteArray(pages * 4)
    bytes(0x04, 0xA1, 0xB2, 0x9F, 0xC3, 0xD4, 0xE5, 0xF6, 0x80, 0x48, 0x00, 0x00, 0xE1, 0x10, 0x3E, 0x00).copyInto(memory)
    data.copyInto(memory, 16)
    return memory
}

/** An NDEF message TLV, with the 3-byte length form for long messages, then a terminator. */
fun ndefTlv(message: ByteArray): ByteArray {
    val length = if (message.size < 0xFF) bytes(message.size) else bytes(0xFF, message.size shr 8, message.size and 0xFF)
    return bytes(0x03) + length + message + bytes(0xFE)
}

/** One short NDEF record (or, when [payload] is 256 bytes or more, a long one). */
fun ndefRecord(tnf: Int, type: String, payload: ByteArray, first: Boolean = true, last: Boolean = true): ByteArray {
    val short = payload.size < 256
    val header = (if (first) 0x80 else 0) or (if (last) 0x40 else 0) or (if (short) 0x10 else 0) or tnf
    val length = if (short) bytes(payload.size) else bytes(payload.size ushr 24, payload.size shr 16 and 0xFF, payload.size shr 8 and 0xFF, payload.size and 0xFF)
    val typeBytes = type.encodeToByteArray()
    return bytes(header, typeBytes.size) + length + typeBytes + payload
}

/** The Android Application Record a vendor's tag carries to launch its app. */
fun launcherRecord(pkg: String, first: Boolean = true, last: Boolean = true) =
    ndefRecord(4, "android.com:pkg", pkg.encodeToByteArray(), first, last)
