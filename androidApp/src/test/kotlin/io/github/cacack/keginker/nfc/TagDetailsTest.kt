package io.github.cacack.keginker.nfc

import kotlin.test.Test
import kotlin.test.assertEquals

/** Checks how [TagDetails] words what each tag technology reports. */
class TagDetailsTest {
    private fun List<TagDetail>.shown() = map { "${it.name}: ${it.value}" }

    @Test
    fun nfcAGivesItsLimits() {
        assertEquals(
            listOf("NfcA max transceive: 253 bytes", "NfcA timeout: 618 ms"),
            TagDetails.nfcA(maxTransceiveLength = 253, timeout = 618).shown(),
        )
    }

    @Test
    fun isoDepGivesItsHiLayerResponseInHex() {
        assertEquals(
            listOf(
                "IsoDep hi-layer response: 00 a1 ff",
                "IsoDep extended-length APDUs: yes",
                "IsoDep max transceive: 65279 bytes",
                "IsoDep timeout: 618 ms",
            ),
            TagDetails.isoDep(byteArrayOf(0x00, 0xA1.toByte(), 0xFF.toByte()), true, 65279, 618).shown(),
        )
    }

    @Test
    fun isoDepLeavesOutAMissingHiLayerResponse() {
        assertEquals(
            listOf("IsoDep extended-length APDUs: no", "IsoDep max transceive: 261 bytes", "IsoDep timeout: 618 ms"),
            TagDetails.isoDep(null, false, 261, 618).shown(),
        )
    }

    @Test
    fun nfcBGivesItsApplicationDataAndProtocolInfo() {
        assertEquals(
            listOf("NfcB application data: 01 02 03 04", "NfcB protocol info: 81 81 41", "NfcB max transceive: 253 bytes"),
            TagDetails.nfcB(byteArrayOf(1, 2, 3, 4), byteArrayOf(0x81.toByte(), 0x81.toByte(), 0x41), 253).shown(),
        )
    }

    @Test
    fun nfcFGivesItsPmmAndSystemCode() {
        assertEquals(
            listOf("NfcF manufacturer (PMm): 01 20 22 04 27 67 4e ff", "NfcF system code: 88 b4", "NfcF max transceive: 254 bytes"),
            TagDetails.nfcF(
                byteArrayOf(0x01, 0x20, 0x22, 0x04, 0x27, 0x67, 0x4E, 0xFF.toByte()),
                byteArrayOf(0x88.toByte(), 0xB4.toByte()),
                254,
            ).shown(),
        )
    }

    @Test
    fun nfcVGivesItsDsfidAndResponseFlagsInHex() {
        assertEquals(
            listOf("NfcV DSFID: 00", "NfcV response flags: 0a", "NfcV max transceive: 253 bytes"),
            TagDetails.nfcV(dsfId = 0, responseFlags = 0x0A, maxTransceiveLength = 253).shown(),
        )
    }

    @Test
    fun mifareTypesAreNamed() {
        assertEquals(listOf("MifareUltralight type: Ultralight C"), TagDetails.mifareUltralight(2).shown())
        assertEquals(listOf("MifareUltralight type: unknown (-1)"), TagDetails.mifareUltralight(-1).shown())
        assertEquals(
            listOf("MifareClassic type: Classic", "MifareClassic size: 1024 bytes", "MifareClassic sectors: 16"),
            TagDetails.mifareClassic(type = 0, size = 1024, sectorCount = 16).shown(),
        )
    }

    @Test
    fun emptyBytesAreMarked() {
        assertEquals("(empty)", TagDetails.nfcB(ByteArray(0), ByteArray(0), 253).first().value)
    }
}
