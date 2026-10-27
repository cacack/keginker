package io.github.cacack.keginker

import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.SimulatedTagLostException
import io.github.cacack.keginker.nfc.TagInfo
import io.github.cacack.keginker.nfc.TagKind
import io.github.cacack.keginker.nfc.Transport

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private val OK = bytes(0x90, 0x00)

/** The EZ Sign "Get Device Information" query, which Report a display sends with vendor commands on. */
private val DEVICE_INFO_QUERY = bytes(0x00, 0xD1, 0x00, 0x00, 0x00)

/** Its reply's A0 TLV: black and white, native 128 × 296. */
private val PANEL_TLV = bytes(0xA0, 0x07, 0xF0, 0x01, 0x20, 0x01, 0x28, 0x00, 0x80)

/** A made-up UID. */
val EZ_SIGN_UID = bytes(0x04, 0x5E, 0x9B, 0x21, 0x7A, 0x33, 0x80)

/** A made-up serial, for an [EzSignTag] that reports one. */
val EZ_SIGN_SERIAL = "ZZQQ123456".encodeToByteArray()

/**
 * A black-and-white EZ Sign display that records what it's sent: it
 * describes itself to the device-information query, accepts every other
 * command, and finishes refreshing at once. With [serial], its
 * device information also carries that serial, in a C0 TLV.
 */
class EzSignTag(serial: ByteArray? = null) : Transport {
    val sent = mutableListOf<ByteArray>()
    private val deviceInfoReply = PANEL_TLV + (serial?.let { bytes(0xC0, it.size) + it } ?: ByteArray(0)) + OK

    /** How many more commands, other than the device-information query, are lost as if the tag moved away. */
    var lostCommands = 0

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        sent += command
        if (lostCommands > 0 && !command.contentEquals(DEVICE_INFO_QUERY)) {
            lostCommands--
            throw SimulatedTagLostException()
        }
        return when {
            command.contentEquals(DEVICE_INFO_QUERY) -> deviceInfoReply
            // The busy poll: not busy.
            command[0] == 0xF0.toByte() && command[1] == 0xDE.toByte() -> bytes(0x00) + OK
            else -> OK
        }
    }
}

/** [tag] as the phone finds it: an ISO-DEP tag KegInker can't write labels to. */
fun foundEzSign(tag: EzSignTag): FoundTag =
    FoundTag.Unsupported(TagInfo(listOf("IsoDep", "NfcA"), EZ_SIGN_UID), TagKind.ISO_DEP) { tag }
