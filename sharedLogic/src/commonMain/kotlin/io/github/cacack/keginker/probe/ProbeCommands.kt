package io.github.cacack.keginker.probe

import io.github.cacack.keginker.nfc.TagKind

// The only commands the display probe sends. Each is a read in the standard
// or datasheet that defines it, so the probe can't change what a tag holds or
// shows: ISO/IEC 7816-4 SELECT and READ BINARY (as NFC Forum Type 4 tags use
// them), the NFC Forum Type 2 READ, NXP's GET_VERSION and READ_SIG, and the
// ISO/IEC 15693-3 Get System Information and Read Single Block. The one
// exception is [VENDOR_QUERIES], sent only when the user opts in.
// See "Display probe" in docs/architecture/protocols.md.

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

/** Whether [command] is a SELECT by AID, as [selectAid] builds. */
internal fun isSelectAid(command: ByteArray): Boolean =
    command.size >= 4 && command[0] == 0x00.toByte() && command[1] == 0xA4.toByte() && command[2] == 0x04.toByte()

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

/**
 * NXP GET_VERSION (NTAG213/215/216 and MIFARE Ultralight EV1 datasheets): the
 * 8-byte vendor, product type, version and storage size.
 */
internal val GET_VERSION = bytes(0x60)

/** NXP READ_SIG at address 00 (the same datasheets): the 32-byte originality signature. */
internal val READ_SIG = bytes(0x3C, 0x00)

/** ISO/IEC 15693-3 request flags: high data rate, not addressed, so our commands never carry the UID. */
private const val NFCV_FLAGS = 0x02

/** ISO/IEC 15693-3 Get System Information: the tag's DSFID, AFI, memory size and IC reference. */
internal val GET_SYSTEM_INFO = bytes(NFCV_FLAGS, 0x2B)

/** ISO/IEC 15693-3 Read Single Block [block] (0–255), without its lock status. */
internal fun readSingleBlock(block: Int): ByteArray {
    require(block in 0..0xFF) { "NFC-V block $block is out of range" }
    return bytes(NFCV_FLAGS, 0x20, block)
}

/**
 * Commands a vendor's own app sends a [kind] of tag, which no standard
 * defines as reads. [source] says where each was found. The probe sends
 * them only in its opt-in vendor pass, after every documented read.
 */
internal class VendorQuery(val name: String, val kind: TagKind, val commands: List<ByteArray>, val source: String)

/**
 * EZ Sign "Get Device Information" (also sent by the Waveshare and Santek
 * apps). INS D1 is WRITE BINARY in ISO/IEC 7816-4, so only vendor behaviour
 * makes it a read. Its reply data is TLVs: A0 the panel geometry and colour
 * mode, A1 more panel flags, C0 a 10-character ASCII serial, C1 a 4-byte
 * device ID. Reports shorten C0 and C1, as they do the UID.
 */
internal val DEVICE_INFO = bytes(0x00, 0xD1, 0x00, 0x00, 0x00)

/**
 * The vendor pass's queries, in the order it sends them. A getter, so each
 * caller gets fresh command arrays it can't change for the next.
 */
internal val VENDOR_QUERIES: List<VendorQuery> get() = listOf(
    VendorQuery(
        name = "EZ Sign device information",
        kind = TagKind.ISO_DEP,
        // The vendor apps send it with the NDEF application selected; no PIN is needed.
        // Expected: `90 00` to the SELECT, then DEVICE_INFO's TLVs and `90 00`.
        commands = listOf(selectAid(NDEF_APPLICATION_AID), DEVICE_INFO.copyOf()),
        source = "niw/EPaperNFCSwift https://github.com/niw/EPaperNFCSwift/blob/" +
            "ee0440e270ee7f4637a20e4f6e0ee4b58695b5df/Sources/EPaperNFCSwift/Command.swift#L117-L121; " +
            "alt-core/nfc-eink https://github.com/alt-core/nfc-eink/blob/" +
            "682519f87aa6e422930d0f7a178eaa2d0d9640da/src/nfc_eink/protocol.py#L76-L82",
    ),
)

internal fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
