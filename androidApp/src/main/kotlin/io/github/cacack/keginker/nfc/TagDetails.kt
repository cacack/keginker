package io.github.cacack.keginker.nfc

import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight

/**
 * What each Android tag technology reports about a tag, as [TagDetail]s for
 * reports. Takes plain values rather than the tag technologies, which Android
 * declares as final classes, so tests can call it. (The `TYPE_*` constants
 * compile to plain values.)
 *
 * Nothing here is an ID: the UID and the NFC-F IDm stay out, since
 * [TagInfo.uid] carries the UID and reports shorten it. Byte values are hex.
 */
internal object TagDetails {
    fun nfcA(maxTransceiveLength: Int, timeout: Int) = listOf(
        TagDetail("NfcA max transceive", "$maxTransceiveLength bytes"),
        TagDetail("NfcA timeout", "$timeout ms"),
    )

    fun isoDep(hiLayerResponse: ByteArray?, extendedLengthApdus: Boolean, maxTransceiveLength: Int, timeout: Int) = listOfNotNull(
        hiLayerResponse?.let { TagDetail("IsoDep hi-layer response", hex(it)) },
        TagDetail("IsoDep extended-length APDUs", if (extendedLengthApdus) "yes" else "no"),
        TagDetail("IsoDep max transceive", "$maxTransceiveLength bytes"),
        TagDetail("IsoDep timeout", "$timeout ms"),
    )

    fun nfcB(applicationData: ByteArray, protocolInfo: ByteArray, maxTransceiveLength: Int) = listOf(
        TagDetail("NfcB application data", hex(applicationData)),
        TagDetail("NfcB protocol info", hex(protocolInfo)),
        TagDetail("NfcB max transceive", "$maxTransceiveLength bytes"),
    )

    /** [manufacturer] is the PMm; the IDm is an ID, so it isn't taken. */
    fun nfcF(manufacturer: ByteArray, systemCode: ByteArray, maxTransceiveLength: Int) = listOf(
        TagDetail("NfcF manufacturer (PMm)", hex(manufacturer)),
        TagDetail("NfcF system code", hex(systemCode)),
        TagDetail("NfcF max transceive", "$maxTransceiveLength bytes"),
    )

    fun nfcV(dsfId: Byte, responseFlags: Byte, maxTransceiveLength: Int) = listOf(
        TagDetail("NfcV DSFID", hex(byteArrayOf(dsfId))),
        TagDetail("NfcV response flags", hex(byteArrayOf(responseFlags))),
        TagDetail("NfcV max transceive", "$maxTransceiveLength bytes"),
    )

    /** [type] is a `MifareUltralight.TYPE_*` value. */
    fun mifareUltralight(type: Int) = listOf(
        TagDetail(
            "MifareUltralight type",
            when (type) {
                MifareUltralight.TYPE_ULTRALIGHT -> "Ultralight"
                MifareUltralight.TYPE_ULTRALIGHT_C -> "Ultralight C"
                else -> "unknown ($type)"
            },
        ),
    )

    /** [type] is a `MifareClassic.TYPE_*` value. */
    fun mifareClassic(type: Int, size: Int, sectorCount: Int) = listOf(
        TagDetail(
            "MifareClassic type",
            when (type) {
                MifareClassic.TYPE_CLASSIC -> "Classic"
                MifareClassic.TYPE_PLUS -> "Plus"
                MifareClassic.TYPE_PRO -> "Pro"
                else -> "unknown ($type)"
            },
        ),
        TagDetail("MifareClassic size", "$size bytes"),
        TagDetail("MifareClassic sectors", "$sectorCount"),
    )

    private fun hex(bytes: ByteArray) =
        if (bytes.isEmpty()) "(empty)" else bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
