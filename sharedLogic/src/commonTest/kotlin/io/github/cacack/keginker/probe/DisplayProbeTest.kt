package io.github.cacack.keginker.probe

import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.TagInfo
import io.github.cacack.keginker.nfc.TagKind
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.nfc.classifyIsoDepTag
import io.github.cacack.keginker.nfc.classifyNfcATag
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest

private val ISO_DEP_INFO = TagInfo(listOf("IsoDep", "NfcA"), bytes(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66), historicalBytes = bytes(0x80))
private val NFC_A_INFO = TagInfo(listOf("NfcA", "MifareUltralight"), bytes(0x04, 0xA1, 0xB2, 0xC3, 0xD4, 0xE5, 0xF6))

private fun isoDep(tag: Transport) = classifyIsoDepTag(ISO_DEP_INFO) { tag }

private fun nfcA(tag: Transport) = classifyNfcATag(NFC_A_INFO) { tag }

private fun ByteArray.page(): Int = this[1].toInt() and 0xFF

class DisplayProbeTest {
    @Test
    fun type4TagReportsItsApplicationCapabilityContainerAndLauncherRecord() = runTest {
        val tag = Type4Tag(launcherRecord("com.example.epaper"))

        val result = probeDisplay(isoDep(tag))

        assertEquals(TagKind.ISO_DEP, result.kind)
        assertContentEquals(NDEF_APPLICATION_AID, result.acceptedAids.single())
        assertEquals(15, assertNotNull(result.capabilityContainer).size)
        val record = assertNotNull(result.ndef).records.single()
        assertEquals("Android app: com.example.epaper", describeNdefRecord(record))
        assertNull(result.note)
        assertEquals(tag.sent.size, result.exchanges.size)
        assertEquals(1, tag.closes)
    }

    @Test
    fun type4ReadsALongMessageInChunksTheTagAllows() = runTest {
        val message = ndefRecord(2, "application/octet-stream", Random(1).nextBytes(400))
        val tag = Type4Tag(message, maxRead = 0x3B)

        val result = probeDisplay(isoDep(tag))

        assertContentEquals(message.copyOfRange(message.size - 400, message.size), assertNotNull(result.ndef).records.single().payload)
        assertTrue(tag.sent.filter { it[1] == 0xB0.toByte() }.all { (it[4].toInt() and 0xFF) <= 0x3B })
    }

    @Test
    fun type4StopsAtTheReadLimitAndSaysSo() = runTest {
        val tag = Type4Tag(ndefRecord(2, "x/y", ByteArray(3_000)))

        val result = probeDisplay(isoDep(tag))

        val read = tag.sent.filter { it[1] == 0xB0.toByte() }.drop(2).sumOf { it[4].toInt() and 0xFF }
        assertEquals(MAX_NDEF_BYTES, read)
        assertTrue(assertNotNull(result.note).contains("only the first $MAX_NDEF_BYTES"))
    }

    @Test
    fun type4WithoutTheNdefApplicationGetsOnlyTheSelect() = runTest {
        val tag = Type4Tag(ByteArray(0), hasNdefApp = false)

        val result = probeDisplay(isoDep(tag))

        assertEquals(PROBE_AIDS.size, tag.sent.size)
        assertTrue(result.acceptedAids.isEmpty())
        assertNull(result.ndef)
        assertEquals("The tag accepted none of the listed applications.", result.note)
    }

    @Test
    fun type2SkipsTheUidPagesAndControlTlvsToFindTheMessage() = runTest {
        val message = launcherRecord("com.example.vendor", last = false) + ndefRecord(1, "U", bytes(0x04) + "example.com/epaper".encodeToByteArray(), first = false)
        // A lock control TLV, a NULL TLV, then the message.
        val tag = Type2Tag(type2Memory(bytes(0x01, 0x03, 0xA0, 0x10, 0x44, 0x00) + ndefTlv(message)))

        val result = probeDisplay(nfcA(tag))

        assertTrue(tag.sent.all { it.page() >= 3 }, "Pages 0-2 hold the full UID")
        assertContentEquals(bytes(0xE1, 0x10, 0x3E, 0x00), result.capabilityContainer)
        assertEquals(
            listOf("Android app: com.example.vendor", "URI: https://example.com/epaper"),
            assertNotNull(result.ndef).records.map(::describeNdefRecord),
        )
        assertTrue(result.acceptedAids.isEmpty())
    }

    @Test
    fun type2ReadsUntilALongTlvIsComplete() = runTest {
        val message = ndefRecord(2, "x/y", Random(2).nextBytes(300))
        val tag = Type2Tag(type2Memory(ndefTlv(message), pages = 128))

        val result = probeDisplay(nfcA(tag))

        assertEquals(1, assertNotNull(result.ndef).records.size)
        assertNull(assertNotNull(result.ndef).error)
    }

    @Test
    fun type2WithoutAMessageSaysSo() = runTest {
        val tag = Type2Tag(type2Memory(bytes(0xFE)))

        val result = probeDisplay(nfcA(tag))

        assertNull(result.ndef)
        assertEquals("The tag's memory holds no NDEF message.", result.note)
        assertEquals(1, tag.sent.size)
    }

    @Test
    fun aTagThatNaksType2ReadsIsReportedAsSuch() = runTest {
        val result = probeDisplay(nfcA(Type2Tag(ByteArray(8))))

        assertEquals("The tag doesn't answer Type 2 reads.", result.note)
    }

    @Test
    fun aWaveshareTagIsProbedOverItsOwnTransport() = runTest {
        val tag = Type2Tag(type2Memory(ndefTlv(launcherRecord("com.waveshare.example"))))

        val result = probeDisplay(FoundTag.Waveshare(tag))

        assertEquals(TagKind.NFC_A, result.kind)
        assertEquals(1, assertNotNull(result.ndef).records.size)
        assertEquals(1, tag.closes)
    }

    @Test
    fun aTagWithNoTransportGetsNothing() = runTest {
        val result = probeDisplay(FoundTag.Unsupported(NFC_A_INFO))

        assertTrue(result.exchanges.isEmpty())
        assertEquals("The probe can't send commands to this kind of tag.", result.note)
    }

    @Test
    fun lostContactEndsTheProbeAndIsRecorded() = runTest {
        val tag = object : ProbeTag() {
            override fun reply(command: ByteArray): ByteArray =
                if (sent.size == 1) OK_REPLY else throw IllegalStateException("Tag was lost")
        }

        val result = probeDisplay(isoDep(tag))

        assertEquals("IllegalStateException: Tag was lost", result.exchanges.last().error)
        assertEquals("Lost contact with the tag while selecting the capability container.", result.note)
        assertEquals(1, tag.closes)
    }

    @Test
    fun cancellationIsPassedOnAndTheTransportClosed() = runTest {
        val tag = object : ProbeTag() {
            override fun reply(command: ByteArray): ByteArray = throw CancellationException("left the screen")
        }

        assertFailsWith<CancellationException> { probeDisplay(isoDep(tag)) }
        assertEquals(1, tag.closes)
    }

    /** Whatever a tag replies, the probe sends only reads, a bounded number of them, and closes the tag. */
    @Test
    fun randomRepliesNeverDrawAnythingButBoundedReads() = runTest {
        repeat(300) { seed ->
            val random = Random(seed)
            val tag = object : ProbeTag() {
                override fun reply(command: ByteArray): ByteArray = when (random.nextInt(6)) {
                    0 -> random.nextBytes(random.nextInt(0, 40))
                    1 -> random.nextBytes(random.nextInt(0, 255)) + OK_REPLY
                    2 -> bytes(0x00, 0x0F, 0x20, 0x00, random.nextInt(256), 0x00, 0xFF, 0x04, 0x06, 0xE1, 0x04, 0xFF, 0xFF, 0x00, 0xFF) + OK_REPLY
                    3 -> bytes(random.nextInt(256), random.nextInt(256)) + OK_REPLY
                    4 -> random.nextBytes(16)
                    else -> OK_REPLY
                }
            }
            val found = if (seed % 2 == 0) isoDep(tag) else nfcA(tag)

            val result = try {
                probeDisplay(found)
            } catch (e: Exception) {
                fail("Seed $seed: the probe threw $e")
            }

            val limit = if (seed % 2 == 0) PROBE_AIDS.size + 5 + MAX_NDEF_BYTES else MAX_TYPE2_READS
            assertTrue(tag.sent.size <= limit, "Seed $seed: ${tag.sent.size} commands")
            assertEquals(tag.sent.size, result.exchanges.size, "Seed $seed")
            assertEquals(1, tag.closes, "Seed $seed")
        }
    }
}

private val OK_REPLY = bytes(0x90, 0x00)
