package io.github.cacack.keginker.nfc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private object NoTransport : Transport {
    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray =
        error("Classification must not talk to the tag")
}

private fun unexpectedTransport(): Transport = error("Unsupported tags get no transport")

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private val WAVESHARE_UID = bytes(0x57, 0x53, 0x44, 0x5A, 0x31, 0x30, 0x6D)

/** As reported by a Rev2.2 board: "YZRCOS", then 03 00. */
private val YZRCOS_HISTORICAL = bytes(0x59, 0x5A, 0x52, 0x43, 0x4F, 0x53, 0x03, 0x00)

class FoundTagTest {
    @Test
    fun waveshareUidIsWaveshare() {
        val tag = classifyNfcATag(WAVESHARE_UID) { NoTransport }

        assertSame(NoTransport, assertIs<FoundTag.Waveshare>(tag).transport)
    }

    @Test
    fun page0WithCheckByteIsNotTheUid() {
        // Page 0 of a Waveshare tag: UID0–2, BCC0, UID3–6.
        val page0 = bytes(0x57, 0x53, 0x44, 0xC8, 0x5A, 0x31, 0x30, 0x6D)

        assertIs<FoundTag.Unsupported>(classifyNfcATag(page0, ::unexpectedTransport))
    }

    @Test
    fun otherUidIsUnsupportedAndKeepsTheUid() {
        val uid = bytes(0x04, 0xA1, 0xB2, 0xC3, 0xD4, 0xE5, 0xF6)

        val tag = assertIs<FoundTag.Unsupported>(classifyNfcATag(uid, ::unexpectedTransport))

        assertTrue(uid.contentEquals(tag.uid))
    }

    @Test
    fun isoDepWithYzrcosHistoricalBytesIsRev22() {
        val tag = classifyIsoDepTag(bytes(0x46, 0x03, 0xFA, 0x76), YZRCOS_HISTORICAL) { NoTransport }

        assertSame(NoTransport, assertIs<FoundTag.WaveshareIsoDep>(tag).transport)
    }

    @Test
    fun otherIsoDepTagIsUnsupported() {
        val tag = classifyIsoDepTag(bytes(0x08, 0x12, 0x34, 0x56), bytes(0x80, 0x73, 0xC8), ::unexpectedTransport)

        assertIs<FoundTag.Unsupported>(tag)
    }

    @Test
    fun isoDepTagWithWaveshareClassicUidIsNotClassic() {
        // The classic UID alone isn't enough once the tag speaks ISO-DEP.
        assertIs<FoundTag.Unsupported>(classifyIsoDepTag(WAVESHARE_UID, ByteArray(0), ::unexpectedTransport))
    }

    @Test
    fun classifyingWithTagInfoAttachesIt() {
        val classic = TagInfo(listOf("NfcA", "MifareUltralight"), WAVESHARE_UID)
        val rev22 = TagInfo(listOf("IsoDep", "NfcA"), bytes(0x46, 0x03, 0xFA, 0x76), historicalBytes = YZRCOS_HISTORICAL)

        assertSame(classic, assertIs<FoundTag.Waveshare>(classifyNfcATag(classic) { NoTransport }).info)
        assertSame(rev22, assertIs<FoundTag.WaveshareIsoDep>(classifyIsoDepTag(rev22) { NoTransport }).info)
    }

    @Test
    fun unsupportedTagKeepsItsTagInfo() {
        val info = TagInfo(listOf("IsoDep"), bytes(0x08, 0x12, 0x34, 0x56))

        val tag = assertIs<FoundTag.Unsupported>(classifyIsoDepTag(info, ::unexpectedTransport))

        assertSame(info, tag.info)
        assertEquals("unsupported", tag.protocolName)
    }

    @Test
    fun classifyingByUidLeavesInfoUnset() {
        assertNull(classifyNfcATag(WAVESHARE_UID) { NoTransport }.info)
    }
}
