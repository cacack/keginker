package io.github.cacack.keginker.probe

// The only commands the display probe sends. Each is a read in the standard
// that defines it, so the probe can't change what a tag holds or shows:
// ISO/IEC 7816-4 SELECT and READ BINARY (as NFC Forum Type 4 tags use them),
// and the NFC Forum Type 2 READ. See "Display probe" in docs/architecture/protocols.md.

/** The NFC Forum NDEF Tag Application, version 2; also listed in the iOS Info.plist. */
internal val NDEF_APPLICATION_AID = bytes(0xD2, 0x76, 0x00, 0x00, 0x85, 0x01, 0x01)

/**
 * The AIDs the probe tries to SELECT, in order. iOS only reports an ISO 7816
 * tag that accepts one of the AIDs in its Info.plist, so the two lists must match.
 */
val PROBE_AIDS: List<ByteArray> get() = listOf(NDEF_APPLICATION_AID.copyOf())

/** The Type 4 capability container file. */
internal val CC_FILE_ID = bytes(0xE1, 0x03)

/** ISO 7816-4 SELECT by DF name (AID), first or only occurrence, with Le. */
internal fun selectAid(aid: ByteArray): ByteArray = bytes(0x00, 0xA4, 0x04, 0x00, aid.size) + aid + bytes(0x00)

/** ISO 7816-4 SELECT of an elementary file by its 2-byte identifier, no data returned. */
internal fun selectFile(fileId: ByteArray): ByteArray {
    require(fileId.size == 2) { "A file identifier is 2 bytes" }
    return bytes(0x00, 0xA4, 0x00, 0x0C, 0x02) + fileId
}

/** ISO 7816-4 READ BINARY of [length] (1–255) bytes at [offset] (below 0x8000) of the selected file. */
internal fun readBinary(offset: Int, length: Int): ByteArray {
    require(offset in 0 until 0x8000) { "READ BINARY offset $offset is out of range" }
    require(length in 1..255) { "READ BINARY length $length is out of range" }
    return bytes(0x00, 0xB0, offset shr 8, offset and 0xFF, length)
}

/** NFC Forum Type 2 READ: 16 bytes from page [page]. */
internal fun type2Read(page: Int): ByteArray {
    require(page in 0..0xFF) { "Type 2 page $page is out of range" }
    return bytes(0x30, page)
}

internal fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
