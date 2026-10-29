package io.github.cacack.keginker.probe

import io.github.cacack.keginker.nfc.TagKind
import io.github.cacack.keginker.nfc.Transport

// Tags simulated from the NFC Forum Type 2, 4 and 5 specs, the NXP NTAG
// datasheets and ISO/IEC 15693-3, for the probe tests. Each one fails the
// test if it's sent anything but a read or one of its vendor queries.

/** Whether [command] is one of the reads in ProbeCommands.kt. */
fun isReadOnly(command: ByteArray): Boolean {
    val c = command.map { it.toInt() and 0xFF }
    return when {
        c.size == 2 && c[0] == 0x30 -> true
        c == listOf(0x60) -> true
        c == listOf(0x3C, 0x00) -> true
        c == listOf(0x02, 0x2B) -> true
        c.size == 3 && c[0] == 0x02 && c[1] == 0x20 -> true
        c.size == 5 && c[0] == 0x00 && c[1] == 0xB0 -> true
        c.size >= 5 && c[0] == 0x00 && c[1] == 0xA4 && c[2] == 0x04 && c[3] == 0x00 && c.size == 6 + c[4] -> true
        c.size == 7 && c[0] == 0x00 && c[1] == 0xA4 && c[2] == 0x00 && c[3] == 0x0C && c[4] == 0x02 -> true
        else -> false
    }
}

/** Whether [command] is one of [queries]' commands. */
internal fun isVendorQuery(command: ByteArray, queries: List<VendorQuery> = VENDOR_QUERIES): Boolean =
    queries.any { query -> query.commands.any { it.contentEquals(command) } }

/** Vendor queries for the tests, since [VENDOR_QUERIES] covers ISO-DEP only: two for NFC-A, one each for ISO-DEP and NFC-V. */
internal val TEST_VENDOR_QUERIES = listOf(
    VendorQuery("Test info", TagKind.NFC_A, listOf(bytes(0xF0, 0xDF, 0x00, 0x00, 0x01), bytes(0xF0, 0xD9)), "test"),
    VendorQuery("Test ISO-DEP", TagKind.ISO_DEP, listOf(bytes(0x90, 0x60, 0x00, 0x00, 0x00)), "test"),
    VendorQuery("Test NFC-V", TagKind.NFC_V, listOf(bytes(0x02, 0xAB, 0x04)), "test"),
)

/** Records what it's sent, and closes once. Accepts the reads in ProbeCommands.kt and [vendorQueries]. */
internal abstract class ProbeTag(private val vendorQueries: List<VendorQuery> = VENDOR_QUERIES) : Transport {
    val sent = mutableListOf<ByteArray>()
    var closes = 0
        private set

    final override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        // An Error, not an Exception, so the probe can't catch it as a lost tag.
        if (!isReadOnly(command) && !isVendorQuery(command, vendorQueries)) {
            throw AssertionError("The probe sent a command that isn't a read: ${command.toList()}")
        }
        sent += command.copyOf()
        return reply(command)
    }

    protected abstract fun reply(command: ByteArray): ByteArray

    override fun close() {
        closes++
    }

    /** The Type 2 READs among [sent]. */
    fun reads(): List<ByteArray> = sent.filter { it[0] == 0x30.toByte() }
}

/**
 * [tag], except that it throws on [silent], as Android's NfcA and NfcV
 * transceive do when a tag NAKs or doesn't answer.
 */
internal fun silentOn(silent: ByteArray, tag: Transport): Transport = object : Transport by tag {
    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray =
        if (command.contentEquals(silent)) throw IllegalStateException("Transceive failed") else tag.transceive(command, timeoutMs)
}

private val OK = bytes(0x90, 0x00)
private val FILE_NOT_FOUND = bytes(0x6A, 0x82)
private val WRONG_PARAMETERS = bytes(0x6B, 0x00)
private val INS_NOT_SUPPORTED = bytes(0x6D, 0x00)
private val NAK = bytes(0x00)

/**
 * A Type 4 tag with the NDEF application, a capability container allowing
 * reads of [maxRead] bytes, and an NDEF file holding [message]. It answers
 * [DEVICE_INFO] with [deviceInfo], or "instruction not supported" when null.
 */
internal class Type4Tag(
    message: ByteArray,
    private val maxRead: Int = 0xFF,
    private val hasNdefApp: Boolean = true,
    private val deviceInfo: ByteArray? = null,
) : ProbeTag() {
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
            command.contentEquals(DEVICE_INFO) -> deviceInfo?.let { it + OK } ?: INS_NOT_SUPPORTED
            else -> {
                val file = selected ?: return FILE_NOT_FOUND
                val offset = c[2] shl 8 or c[3]
                val length = c[4]
                // A read as long as the capability container always works, so a tiny maxRead still lets the probe read it.
                if (length > maxOf(maxRead, cc.size) || offset >= file.size) return WRONG_PARAMETERS
                file.copyOfRange(offset, minOf(file.size, offset + length)) + OK
            }
        }
    }
}

/**
 * A Type 2 tag whose memory from page 0 is [memory]; a read past the end gets
 * a NAK. It answers NXP GET_VERSION and READ_SIG with [version] and
 * [signature], or a NAK when null, and anything else with a NAK.
 */
internal class Type2Tag(
    private val memory: ByteArray,
    private val version: ByteArray? = null,
    private val signature: ByteArray? = null,
    vendorQueries: List<VendorQuery> = VENDOR_QUERIES,
) : ProbeTag(vendorQueries) {
    override fun reply(command: ByteArray): ByteArray {
        when {
            command.contentEquals(GET_VERSION) -> return version ?: NAK
            command.contentEquals(READ_SIG) -> return signature ?: NAK
            command[0] != 0x30.toByte() -> return NAK
        }
        val start = (command[1].toInt() and 0xFF) * 4
        if (start >= memory.size) return NAK
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

/**
 * A [DEVICE_INFO] reply's data, laid out as niw/EPaperNFCSwift and
 * alt-core/nfc-eink parse it: A0 (4-color, 400×300), A1, the C0 serial
 * "SEAA000282" and a C1 ID.
 */
val EZ_SIGN_DEVICE_INFO = bytes(0xA0, 0x07, 0x00, 0x07, 0x14, 0x02, 0x58, 0x01, 0x90) +
    bytes(0xA1, 0x07, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) +
    bytes(0xC0, 0x0A) + "SEAA000282".encodeToByteArray() +
    bytes(0xC1, 0x04, 0x12, 0x34, 0xAB, 0xCD)

/** A made-up C0 serial and C1 ID for the real panels' replies below. */
val MADE_UP_SERIAL = "ZZQQ123456".encodeToByteArray()
val MADE_UP_ID = bytes(0x5A, 0xC3, 0x7E, 0x91)

/** The TLVs a real EZ Sign reply carries besides A0 and B1, then a made-up C0 and C1. */
private fun ezSignTlvsAfter(b1: Int) = bytes(0xA1, 0x07, 0x01, 0x12, 0x00, 0x30, 0xFF, 0xFF, 0xFF) +
    bytes(0xB1, 0x01, b1, 0xB2, 0x01, 0x14, 0xB3, 0x01, 0x00) +
    bytes(0xD1, 0x07, 0x01, 0x20, 0x00, 0x00, 0x00, 0x00, 0x00) +
    bytes(0xC0, 0x0A) + MADE_UP_SERIAL + bytes(0xC1, 0x04) + MADE_UP_ID

/** A 2.9" EZ Sign panel's [DEVICE_INFO] reply data: 4 colors, native 128 × 296. */
val EZ_SIGN_29_DEVICE_INFO = bytes(0xA0, 0x07, 0xF0, 0x07, 0x20, 0x02, 0x50, 0x00, 0x80) + ezSignTlvsAfter(0x1A)

/** A 4.2" EZ Sign panel's [DEVICE_INFO] reply data: 4 colors, native 400 × 300. */
val EZ_SIGN_42_DEVICE_INFO = bytes(0xA0, 0x07, 0xF0, 0x07, 0x20, 0x02, 0x58, 0x01, 0x90) + ezSignTlvsAfter(0x08)

/** An NTAG213's GET_VERSION reply: NXP, NTAG, 50 pF, version 1.0, 128 to 256 bytes, ISO 14443-3. */
val NTAG213_VERSION = bytes(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x0F, 0x03)

/** An NFC-V UID as it's written, most significant byte (always E0) first. */
val NFCV_UID = bytes(0xE0, 0x04, 0x01, 0x50, 0x12, 0x34, 0x56, 0x78)

/**
 * An NFC-V tag whose memory from block 0 is [memory], in blocks of
 * [blockSize] bytes; a read past the end gets an error reply. Its system
 * information declares [declaredBlocks] blocks, unless [answersSystemInfo] is false.
 */
internal class NfcVTag(
    private val memory: ByteArray,
    private val blockSize: Int = 4,
    private val declaredBlocks: Int = memory.size / blockSize,
    private val answersSystemInfo: Boolean = true,
    vendorQueries: List<VendorQuery> = VENDOR_QUERIES,
) : ProbeTag(vendorQueries) {
    override fun reply(command: ByteArray): ByteArray {
        val c = command.map { it.toInt() and 0xFF }
        return when {
            // Flags; info flags (DSFID, AFI, memory size, IC reference); the UID, least significant byte first; then each field.
            c[1] == 0x2B && answersSystemInfo ->
                bytes(0x00, 0x0F) + NFCV_UID.reversedArray() + bytes(0x00, 0x00, declaredBlocks - 1, blockSize - 1, 0x01)
            c[1] == 0x20 && (c[2] + 1) * blockSize <= memory.size ->
                bytes(0x00) + memory.copyOfRange(c[2] * blockSize, (c[2] + 1) * blockSize)
            // The error flag, then "block not available".
            c[1] == 0x20 -> bytes(0x01, 0x10)
            // The error flag, then "command not supported".
            else -> bytes(0x01, 0x01)
        }
    }
}

/** Type 5 memory: a 4-byte capability container for [size] bytes, then [data], then zeros. */
fun type5Memory(data: ByteArray, size: Int = 112): ByteArray {
    val memory = ByteArray(size)
    bytes(0xE1, 0x40, size / 8, 0x01).copyInto(memory)
    data.copyInto(memory, 4)
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
