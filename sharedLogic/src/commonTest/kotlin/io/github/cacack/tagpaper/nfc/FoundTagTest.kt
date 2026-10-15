package io.github.cacack.tagpaper.nfc

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

private object NoTransport : Transport {
    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray =
        error("Classification must not talk to the tag")
}

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

class FoundTagTest {
    @Test
    fun waveshareUidIsWaveshare() {
        val tag = classifyTag(bytes(0x57, 0x53, 0x44, 0x5A, 0x31, 0x30, 0x6D), NoTransport)

        assertSame(NoTransport, assertIs<FoundTag.Waveshare>(tag).transport)
    }

    @Test
    fun page0WithCheckByteIsNotTheUid() {
        // Page 0 of a Waveshare tag: UID0–2, BCC0, UID3–6.
        val page0 = bytes(0x57, 0x53, 0x44, 0xC8, 0x5A, 0x31, 0x30, 0x6D)

        assertIs<FoundTag.Unsupported>(classifyTag(page0, NoTransport))
    }

    @Test
    fun otherUidIsUnsupportedAndKeepsTheUid() {
        val uid = bytes(0x04, 0xA1, 0xB2, 0xC3, 0xD4, 0xE5, 0xF6)

        val tag = assertIs<FoundTag.Unsupported>(classifyTag(uid, NoTransport))

        assertTrue(uid.contentEquals(tag.uid))
    }
}
