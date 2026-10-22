package io.github.cacack.keginker.probe

import io.github.cacack.keginker.waveshare.toShownHex

/** Most payload bytes a report shows of one record. */
private const val PAYLOAD_SHOWN_BYTES = 64

/** One NDEF record. [tnf] is the 3-bit Type Name Format. */
class NdefRecord(val tnf: Int, val type: ByteArray, val payload: ByteArray)

/** The records parsed from an NDEF message, and why parsing stopped early, if it did. */
class NdefMessage(val records: List<NdefRecord>, val error: String?)

/**
 * Parses an NDEF message (NFC Forum NDEF 1.0). Chunked records are rejected,
 * since tags don't use them. Never throws: a malformed message keeps the
 * records before the fault and says what was wrong.
 */
fun parseNdefMessage(message: ByteArray): NdefMessage {
    val records = mutableListOf<NdefRecord>()
    var at = 0
    fun u8(): Int {
        if (at >= message.size) throw NdefFault("ends inside a record header")
        return message[at++].toInt() and 0xFF
    }
    fun take(count: Long): ByteArray {
        if (count > message.size - at) throw NdefFault("a record is longer than the message")
        return message.copyOfRange(at, at + count.toInt()).also { at += count.toInt() }
    }
    return try {
        while (at < message.size) {
            val header = u8()
            if (header and 0x20 != 0) throw NdefFault("chunked records aren't supported")
            val typeLength = u8()
            val payloadLength = if (header and 0x10 != 0) u8().toLong() else (0 until 4).fold(0L) { n, _ -> (n shl 8) or u8().toLong() }
            val idLength = if (header and 0x08 != 0) u8() else 0
            val type = take(typeLength.toLong())
            take(idLength.toLong())
            val payload = take(payloadLength)
            records += NdefRecord(header and 0x07, type, payload)
            if (header and 0x40 != 0) break
        }
        NdefMessage(records, null)
    } catch (fault: NdefFault) {
        NdefMessage(records, fault.message)
    }
}

private class NdefFault(message: String) : Exception(message)

/** [record] as one report line: its kind, then its payload as text where that's how it's defined. */
fun describeNdefRecord(record: NdefRecord): String {
    val type = record.type.decodeToString()
    return when {
        record.tnf == TNF_WELL_KNOWN && type == "U" && record.payload.isNotEmpty() ->
            "URI: ${uriPrefix(record.payload[0].toInt() and 0xFF)}${shownText(record.payload.copyOfRange(1, record.payload.size))}"
        record.tnf == TNF_WELL_KNOWN && type == "T" && record.payload.isNotEmpty() -> {
            val languageLength = record.payload[0].toInt() and 0x3F
            val start = (1 + languageLength).coerceAtMost(record.payload.size)
            "Text: ${shownText(record.payload.copyOfRange(start, record.payload.size))}"
        }
        record.tnf == TNF_EXTERNAL && type == "android.com:pkg" -> "Android app: ${shownText(record.payload)}"
        else -> "${tnfName(record.tnf)} \"${shownText(record.type)}\": ${shownHex(record.payload)}"
    }
}

private const val TNF_WELL_KNOWN = 1
private const val TNF_EXTERNAL = 4

private fun tnfName(tnf: Int) = when (tnf) {
    0 -> "Empty"
    TNF_WELL_KNOWN -> "Well-known type"
    2 -> "Media type"
    3 -> "Absolute URI"
    TNF_EXTERNAL -> "External type"
    5 -> "Unknown type"
    6 -> "Unchanged type"
    else -> "Reserved type"
}

/**
 * Text, cut to [PAYLOAD_SHOWN_BYTES], with each character that is invisible
 * or breaks a line shown as `?`, so a record can't fake report lines or show
 * other text than it holds.
 */
private fun shownText(bytes: ByteArray): String {
    val text = bytes.copyOfRange(0, minOf(bytes.size, PAYLOAD_SHOWN_BYTES)).decodeToString()
    val shown = buildString {
        var at = 0
        while (at < text.length) {
            val c = text[at++]
            when {
                c.isISOControl() || c.category in HIDDEN_CATEGORIES -> append('?')
                // Plane 14 (U+E0000 to U+E0FFF) holds only invisible tag and variation selector characters.
                c in '\uDB40'..'\uDB43' -> {
                    append('?')
                    if (at < text.length && text[at].isLowSurrogate()) at++
                }
                else -> append(c)
            }
        }
    }
    return if (bytes.size > PAYLOAD_SHOWN_BYTES) "$shown … (${bytes.size} bytes)" else shown
}

/** Unicode format characters (Cf: bidi controls, zero-width spaces and the like), and line and paragraph separators. */
private val HIDDEN_CATEGORIES = setOf(CharCategory.FORMAT, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR)

private fun shownHex(bytes: ByteArray): String = if (bytes.isEmpty()) "(empty)" else bytes.toShownHex(PAYLOAD_SHOWN_BYTES)

/** NFC Forum URI Record Type Definition prefix codes. */
private fun uriPrefix(code: Int): String = URI_PREFIXES.getOrNull(code) ?: ""

private val URI_PREFIXES = listOf(
    "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:", "ftp://anonymous:anonymous@",
    "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://", "ftp://", "dav://", "news:", "telnet://", "imap:",
    "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://",
    "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
)
