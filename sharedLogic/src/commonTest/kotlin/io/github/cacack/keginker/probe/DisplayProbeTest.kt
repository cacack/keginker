package io.github.cacack.keginker.probe

import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.TagInfo
import io.github.cacack.keginker.nfc.TagKind
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.nfc.classifyIsoDepTag
import io.github.cacack.keginker.nfc.classifyNfcATag
import io.github.cacack.keginker.nfc.classifyNfcVTag
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

private val ISO_DEP_INFO = TagInfo(listOf("IsoDep", "NfcA"), bytes(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66), historicalBytes = bytes(0x80))
private val NFC_A_INFO = TagInfo(listOf("NfcA", "MifareUltralight"), bytes(0x04, 0xA1, 0xB2, 0xC3, 0xD4, 0xE5, 0xF6))

private fun isoDep(tag: Transport) = classifyIsoDepTag(ISO_DEP_INFO) { tag }

private fun nfcA(tag: Transport) = classifyNfcATag(NFC_A_INFO) { tag }

private fun nfcV(tag: Transport) = classifyNfcVTag(TagInfo(listOf("NfcV"), NFCV_UID.reversedArray())) { tag }

private fun ByteArray.page(): Int = this[1].toInt() and 0xFF

/** Where [type2Memory] keeps capability container byte 2, the data area's size in 8-byte units (page 3, byte 2). */
private const val CC_DATA_SIZE = 14

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

        assertTrue(tag.reads().all { it.page() >= 3 }, "Pages 0-2 hold the full UID")
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
    fun type2ReadsStayInsideTheDataAreaSoTheyNeverWrapIntoTheUid() = runTest {
        // An Ultralight: 16 pages, a 48-byte data area (pages 4–15), all NULL TLVs.
        val memory = type2Memory(ByteArray(0), pages = 16)
        memory[CC_DATA_SIZE] = 0x06 // 6 × 8 bytes
        val tag = Type2Tag(memory)

        val result = probeDisplay(nfcA(tag))

        assertEquals(listOf(3, 7, 11, 12), tag.reads().map { it.page() })
        val uid = memory.copyOfRange(0, 8)
        assertTrue(result.exchanges.none { e -> e.reply!!.toList().windowed(8).any { it == uid.toList() } }, "The full UID leaked")
        assertEquals("No complete NDEF message in the tag's data area.", result.note)
    }

    @Test
    fun type2WithoutADataAreaSaysSo() = runTest {
        val memory = type2Memory(ByteArray(0), pages = 16)
        memory[CC_DATA_SIZE] = 0x00
        val tag = Type2Tag(memory)

        val result = probeDisplay(nfcA(tag))

        assertEquals(1, tag.reads().size)
        assertEquals("The capability container declares no data area.", result.note)
    }

    @Test
    fun type2WithoutACapabilityContainerStopsBeforeItCouldWrap() = runTest {
        // A blank tag: its CC page is junk, so its declared size can't be trusted.
        val memory = type2Memory(ByteArray(0), pages = 16)
        bytes(0x00, 0x00, 0xFF, 0x00).copyInto(memory, CC_DATA_SIZE - 2)
        val tag = Type2Tag(memory)

        val result = probeDisplay(nfcA(tag))

        assertEquals(1, tag.reads().size)
        assertEquals("The tag has no NDEF capability container.", result.note)
    }

    @Test
    fun type2WithoutAMessageSaysSo() = runTest {
        val tag = Type2Tag(type2Memory(bytes(0xFE)))

        val result = probeDisplay(nfcA(tag))

        assertNull(result.ndef)
        assertEquals("The tag's memory holds no NDEF message.", result.note)
        assertEquals(1, tag.reads().size)
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

    @OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime
    @Test
    fun aProbeThatOverrunsTheDeadlineStopsAndSaysSo() = runTest {
        // 15-byte reads make about 70 reads, each just inside the per-command timeout.
        val tag = Type4Tag(ndefRecord(2, "x/y", ByteArray(1_000)), maxRead = 15)
        val slowTag = object : Transport by tag {
            override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
                delay(timeoutMs - 100)
                return tag.transceive(command, timeoutMs)
            }
        }

        val result = probeDisplay(isoDep(slowTag))

        assertTrue(testScheduler.currentTime <= PROBE_DEADLINE_MS, "Stopped at ${testScheduler.currentTime} ms")
        assertEquals("The tag answered too slowly; the probe stopped after ${PROBE_DEADLINE_MS / 1_000} s.", result.note)
        assertNull(result.ndef)
        assertNotNull(result.exchanges.last().error)
        assertEquals(1, tag.closes)
    }

    @Test
    fun type2ReadsTheNxpIdentityAfterTheNdefMessage() = runTest {
        val signature = Random(3).nextBytes(32)
        val tag = Type2Tag(type2Memory(ndefTlv(launcherRecord("com.example.vendor"))), NTAG213_VERSION, signature)

        val result = probeDisplay(nfcA(tag))

        assertEquals(listOf(ProbeStep.READ_PAGES, ProbeStep.GET_VERSION, ProbeStep.READ_SIGNATURE), result.exchanges.map { it.step }.distinct())
        assertContentEquals(NTAG213_VERSION, result.version)
        assertContentEquals(signature, result.signature)
        assertEquals(1, assertNotNull(result.ndef).records.size)
        assertNull(result.note)
    }

    @Test
    fun aTagThatNaksGetVersionStillHasItsNdefMessageRead() = runTest {
        // Like a Waveshare classic board: not an NXP tag, so it NAKs both identity reads.
        val tag = Type2Tag(type2Memory(ndefTlv(launcherRecord("com.waveshare.example"))))

        val result = probeDisplay(FoundTag.Waveshare(tag))

        assertEquals(1, assertNotNull(result.ndef).records.size)
        assertNull(result.version)
        assertNull(result.signature)
        assertEquals(listOf(ProbeStep.GET_VERSION, ProbeStep.READ_SIGNATURE), result.exchanges.takeLast(2).map { it.step })
        assertNull(result.note)
    }

    @Test
    fun aTagSilentOnGetVersionStillGetsReadSigAndTheVendorPass() = runTest {
        val signature = Random(4).nextBytes(32)
        val tag = Type2Tag(type2Memory(ndefTlv(launcherRecord("com.example.vendor"))), signature = signature, vendorQueries = TEST_VENDOR_QUERIES)

        val result = probeDisplay(nfcA(silentOn(GET_VERSION, tag)), vendorQueries = true, queries = TEST_VENDOR_QUERIES)

        assertEquals(1, assertNotNull(result.ndef).records.size)
        assertEquals("The tag didn't answer GET_VERSION.", result.note)
        val identity = result.exchanges.filter { it.step == ProbeStep.GET_VERSION || it.step == ProbeStep.READ_SIGNATURE }
        assertEquals("IllegalStateException: Transceive failed", identity.first().error)
        assertNull(result.version)
        assertContentEquals(signature, result.signature)
        assertEquals(2, result.exchanges.count { it.pass == ProbePass.VENDOR })
        assertEquals(1, tag.closes)
    }

    @Test
    fun anNfcVTagSilentOnSystemInformationStillHasItsBlocksRead() = runTest {
        val tag = NfcVTag(type5Memory(ndefTlv(launcherRecord("com.example.epaper"))))

        val result = probeDisplay(nfcV(silentOn(GET_SYSTEM_INFO, tag)))

        assertNull(result.systemInfo)
        assertEquals("IllegalStateException: Transceive failed", result.exchanges.first().error)
        // Read until the tag refuses block 28, past its end.
        assertEquals(29, tag.sent.count { it[1] == 0x20.toByte() })
        assertEquals(112, assertNotNull(result.memory).size)
        assertEquals("Android app: com.example.epaper", describeNdefRecord(assertNotNull(result.ndef).records.single()))
        assertEquals("The tag didn't answer GET_SYSTEM_INFO.", result.note)
    }

    @Test
    fun lostContactOnANeededReadSkipsTheVendorPass() = runTest {
        val tag = Type4Tag(launcherRecord("com.example.epaper"), deviceInfo = EZ_SIGN_DEVICE_INFO)

        val result = probeDisplay(isoDep(silentOn(readBinary(0, 15), tag)), vendorQueries = true)

        assertTrue(result.exchanges.none { it.pass == ProbePass.VENDOR })
        assertEquals("contact with the tag was lost", result.vendorPassSkipped)
        assertEquals("Lost contact with the tag while reading the capability container. The vendor queries weren't sent.", result.note)
    }

    @Test
    fun aRefusedSelectEndsItsVendorQuery() = runTest {
        // Without the NDEF application, the EZ Sign query's SELECT is refused, so its D1 isn't sent.
        val tag = Type4Tag(ByteArray(0), hasNdefApp = false)

        val result = probeDisplay(isoDep(tag), vendorQueries = true)

        val vendor = result.exchanges.filter { it.pass == ProbePass.VENDOR }
        assertEquals(listOf(selectAid(NDEF_APPLICATION_AID).toList()), vendor.map { it.command.toList() })
        assertEquals(listOf(ProbeStep.SELECT_AID), vendor.map { it.step }, "A vendor query's SELECT is labelled as one")
        assertTrue(tag.sent.none { it.contentEquals(DEVICE_INFO) })
        assertEquals(1, result.vendorQueriesSent)
    }

    @Test
    fun nfcVSystemInformationIsParsedAndBlocksReadUpToTheDeclaredCount() = runTest {
        val tag = NfcVTag(type5Memory(ndefTlv(launcherRecord("com.example.epaper"))))

        val result = probeDisplay(nfcV(tag))

        assertEquals(TagKind.NFC_V, result.kind)
        val info = assertNotNull(result.systemInfo)
        assertEquals(listOf(0, 0, 28, 4, 1), listOf(info.dsfid, info.afi, info.blockCount, info.blockSize, info.icReference))
        assertEquals(1 + 28, tag.sent.size)
        assertEquals(112, assertNotNull(result.memory).size)
        assertContentEquals(bytes(0xE1, 0x40, 0x0E, 0x01), result.capabilityContainer)
        assertEquals("Android app: com.example.epaper", describeNdefRecord(assertNotNull(result.ndef).records.single()))
        assertNull(result.note)
        assertTrue(tag.sent.none { it.size > 3 }, "Every command is non-addressed, so none carries the UID")
    }

    @Test
    fun nfcVReadsNoMoreThanTheBlockCap() = runTest {
        val tag = NfcVTag(type5Memory(ByteArray(0), size = 1_024), declaredBlocks = 256)

        val result = probeDisplay(nfcV(tag))

        assertEquals(MAX_NFCV_BLOCKS, tag.sent.count { it[1] == 0x20.toByte() })
        assertEquals(MAX_NFCV_BLOCKS * 4, assertNotNull(result.memory).size)
        assertTrue(assertNotNull(result.note).startsWith("The tag has 256 blocks; only the first $MAX_NFCV_BLOCKS were read."), result.note)
    }

    @Test
    fun nfcVStopsAtTheFirstErrorReply() = runTest {
        val tag = NfcVTag(type5Memory(ByteArray(0), size = 40), declaredBlocks = 28)

        val result = probeDisplay(nfcV(tag))

        assertEquals(11, tag.sent.count { it[1] == 0x20.toByte() })
        assertEquals(40, assertNotNull(result.memory).size)
        assertTrue(assertNotNull(result.note).startsWith("The tag refused to read block 10."), result.note)
    }

    @Test
    fun nfcVWithoutSystemInformationIsReadUntilItRefuses() = runTest {
        val tag = NfcVTag(bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08), answersSystemInfo = false)

        val result = probeDisplay(nfcV(tag))

        assertNull(result.systemInfo)
        assertEquals(1 + 3, tag.sent.size)
        assertContentEquals(bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08), result.memory)
        assertEquals("The tag has no NDEF capability container.", result.note)
    }

    @Test
    fun theVendorPassDoesNotRunWhenOff() = runTest {
        val tag = Type2Tag(type2Memory(ndefTlv(launcherRecord("com.example.vendor"))), vendorQueries = TEST_VENDOR_QUERIES)

        val result = probeDisplay(nfcA(tag), vendorQueries = false, queries = TEST_VENDOR_QUERIES)

        assertTrue(result.exchanges.all { it.pass == ProbePass.DOCUMENTED })
        assertTrue(tag.sent.none { isVendorQuery(it, TEST_VENDOR_QUERIES) })
        assertNull(result.vendorQueriesListed)
    }

    @Test
    fun theVendorPassRunsAfterEveryDocumentedReadWhenOn() = runTest {
        val tag = Type2Tag(type2Memory(ndefTlv(launcherRecord("com.example.vendor"))), vendorQueries = TEST_VENDOR_QUERIES)

        val result = probeDisplay(nfcA(tag), vendorQueries = true, queries = TEST_VENDOR_QUERIES)

        val passes = result.exchanges.map { it.pass }
        assertEquals(passes.sortedBy { it.ordinal }, passes, "Every documented read comes first")
        val vendor = result.exchanges.filter { it.pass == ProbePass.VENDOR }
        assertEquals(TEST_VENDOR_QUERIES[0].commands.map { it.toList() }, vendor.map { it.command.toList() })
        assertTrue(vendor.all { it.step == ProbeStep.VENDOR_QUERY })
        assertEquals(1, result.vendorQueriesListed)
        assertEquals(1, result.vendorQueriesSent)
        assertEquals(1, assertNotNull(result.ndef).records.size)
        assertNull(result.note)
    }

    @Test
    fun theVendorPassRunsAfterADocumentedStop() = runTest {
        val tag = Type2Tag(ByteArray(8), vendorQueries = TEST_VENDOR_QUERIES)

        val result = probeDisplay(nfcA(tag), vendorQueries = true, queries = TEST_VENDOR_QUERIES)

        assertEquals("The tag doesn't answer Type 2 reads.", result.note)
        assertEquals(2, result.exchanges.count { it.pass == ProbePass.VENDOR })
    }

    @Test
    fun lostContactInTheVendorPassKeepsTheDocumentedData() = runTest {
        val tag = Type2Tag(type2Memory(ndefTlv(launcherRecord("com.example.vendor"))), NTAG213_VERSION, vendorQueries = TEST_VENDOR_QUERIES)
        val losingTag = object : Transport by tag {
            override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray =
                if (isVendorQuery(command, TEST_VENDOR_QUERIES)) throw IllegalStateException("Tag was lost") else tag.transceive(command, timeoutMs)
        }

        val result = probeDisplay(nfcA(losingTag), vendorQueries = true, queries = TEST_VENDOR_QUERIES)

        assertEquals(1, assertNotNull(result.ndef).records.size)
        assertContentEquals(NTAG213_VERSION, result.version)
        assertEquals("Lost contact with the tag while sending a vendor query.", result.note)
        assertEquals(1, result.exchanges.count { it.pass == ProbePass.VENDOR }, "The pass ends at the lost contact")
        assertEquals(1, tag.closes)
    }

    @Test
    fun anEmptyVendorListSendsNothingMore() = runTest {
        val tag = Type2Tag(type2Memory(ndefTlv(launcherRecord("com.example.vendor"))))

        val result = probeDisplay(nfcA(tag), vendorQueries = true, queries = emptyList())

        assertTrue(result.exchanges.all { it.pass == ProbePass.DOCUMENTED })
        assertEquals(0, result.vendorQueriesListed)
    }

    @Test
    fun theListedQueryReadsDeviceInformationAfterTheDocumentedReads() = runTest {
        val tag = Type4Tag(launcherRecord("com.example.epaper"), deviceInfo = EZ_SIGN_DEVICE_INFO)

        val result = probeDisplay(isoDep(tag), vendorQueries = true)

        val passes = result.exchanges.map { it.pass }
        assertEquals(passes.sortedBy { it.ordinal }, passes)
        val vendor = result.exchanges.filter { it.pass == ProbePass.VENDOR }
        assertEquals(listOf(selectAid(NDEF_APPLICATION_AID), DEVICE_INFO).map { it.toList() }, vendor.map { it.command.toList() })
        assertContentEquals(EZ_SIGN_DEVICE_INFO + OK_REPLY, vendor.last().reply)
        assertEquals(1, result.vendorQueriesListed)
        assertEquals(1, assertNotNull(result.ndef).records.size)
    }

    @Test
    fun aDecodedDeviceInformationReplyNamesTheFamily() = runTest {
        val result = probeDisplay(isoDep(Type4Tag(launcherRecord("x"), deviceInfo = EZ_SIGN_29_DEVICE_INFO)), vendorQueries = true)

        val info = assertNotNull(result.deviceInfo)
        assertEquals(296 to 128, info.viewWidth to info.viewHeight)
        assertNull(result.deviceInfoNotDecoded)
        assertEquals("EZ Sign (ISO-DEP), no writer yet", result.protocol)
    }

    @Test
    fun aRefusedDeviceInformationQuerySaysWhyAndKeepsTheProtocol() = runTest {
        val result = probeDisplay(isoDep(Type4Tag(launcherRecord("x"))), vendorQueries = true)

        assertNull(result.deviceInfo)
        assertEquals("the tag refused the query (status 6D 00)", result.deviceInfoNotDecoded)
        assertEquals("unsupported", result.protocol)
    }

    @Test
    fun deviceInformationIsOnlyDecodedWhenTheQueryWasSent() = runTest {
        val off = probeDisplay(isoDep(Type4Tag(launcherRecord("x"), deviceInfo = EZ_SIGN_29_DEVICE_INFO)), vendorQueries = false)
        // The SELECT is refused, so the query that relies on it isn't sent.
        val unselected = probeDisplay(isoDep(Type4Tag(ByteArray(0), hasNdefApp = false, deviceInfo = EZ_SIGN_29_DEVICE_INFO)), vendorQueries = true)

        listOf(off, unselected).forEach {
            assertNull(it.deviceInfo)
            assertNull(it.deviceInfoNotDecoded)
            assertEquals("unsupported", it.protocol)
        }
    }

    @Test
    fun theListedQueriesSendNothingToOtherKinds() = runTest {
        val nfcATag = Type2Tag(type2Memory(ndefTlv(launcherRecord("com.example.vendor"))))
        val nfcVTag = NfcVTag(type5Memory(ndefTlv(launcherRecord("com.example.vendor"))))

        val results = listOf(probeDisplay(nfcA(nfcATag), vendorQueries = true), probeDisplay(nfcV(nfcVTag), vendorQueries = true))

        results.forEach {
            assertTrue(it.exchanges.all { exchange -> exchange.pass == ProbePass.DOCUMENTED })
            assertEquals(0, it.vendorQueriesListed)
        }
    }

    @Test
    fun theListedQueriesAreSourcedAndLeaveOutThePanelConfigWrite() {
        assertTrue(VENDOR_QUERIES.isNotEmpty())
        VENDOR_QUERIES.forEach { query ->
            assertTrue(query.commands.isNotEmpty(), query.name)
            assertTrue("https://github.com/" in query.source, query.name)
            // Waveshare's F0 D8 sets the panel type; it's never sent.
            assertTrue(query.commands.none { it.size >= 2 && it[0] == 0xF0.toByte() && it[1] == 0xD8.toByte() }, query.name)
        }
    }

    @Test
    fun theExchangeCapHoldsAndSkipsTheVendorPass() = runTest {
        // One-byte NDEF reads would take a thousand commands.
        val tag = Type4Tag(ndefRecord(2, "x/y", ByteArray(1_000)), maxRead = 1)

        val result = probeDisplay(isoDep(tag), vendorQueries = true, queries = TEST_VENDOR_QUERIES)

        assertEquals(MAX_PROBE_EXCHANGES, tag.sent.size)
        assertEquals(MAX_PROBE_EXCHANGES, result.exchanges.size)
        assertTrue(result.exchanges.all { it.pass == ProbePass.DOCUMENTED })
        assertEquals("The probe stopped at its limit of $MAX_PROBE_EXCHANGES commands.", result.note)
        assertEquals("the probe reached its command limit", result.vendorPassSkipped)
        assertEquals(1, tag.closes)
    }

    @Test
    fun type2StopsAtItsReadCap() = runTest {
        // The largest data area a CC can declare, all NULL TLVs, so the message is never complete.
        val memory = type2Memory(ByteArray(0), pages = 256)
        memory[CC_DATA_SIZE] = 0xFF.toByte()
        val tag = Type2Tag(memory)

        val result = probeDisplay(nfcA(tag))

        assertEquals(MAX_TYPE2_READS, tag.reads().size)
        assertEquals("No complete NDEF message in the first ${MAX_TYPE2_READS * 16} bytes read.", result.note)
    }

    @Test
    fun theExchangeCapHoldsAcrossBothPasses() = runTest {
        val queries = listOf(VendorQuery("Many", TagKind.NFC_A, List(MAX_PROBE_EXCHANGES) { bytes(0xF0, 0xD9) }, "test"))
        val tag = Type2Tag(type2Memory(ndefTlv(launcherRecord("com.example.vendor"))), vendorQueries = queries)

        val result = probeDisplay(nfcA(tag), vendorQueries = true, queries = queries)

        assertEquals(MAX_PROBE_EXCHANGES, tag.sent.size)
        assertEquals(1, assertNotNull(result.ndef).records.size)
        assertEquals("The probe stopped at its limit of $MAX_PROBE_EXCHANGES commands.", result.note)
    }

    /**
     * Whatever a tag replies, the probe sends only reads (and, when asked,
     * vendor queries after them), a bounded number of them, and closes the tag.
     */
    @Test
    fun randomRepliesNeverDrawAnythingButBoundedReads() = runTest {
        repeat(600) { seed ->
            val random = Random(seed)
            val tag = object : ProbeTag(TEST_VENDOR_QUERIES) {
                override fun reply(command: ByteArray): ByteArray = when (random.nextInt(8)) {
                    0 -> random.nextBytes(random.nextInt(0, 40))
                    1 -> random.nextBytes(random.nextInt(0, 255)) + OK_REPLY
                    2 -> bytes(0x00, 0x0F, 0x20, 0x00, random.nextInt(256), 0x00, 0xFF, 0x04, 0x06, 0xE1, 0x04, 0xFF, 0xFF, 0x00, 0xFF) + OK_REPLY
                    3 -> bytes(random.nextInt(256), random.nextInt(256)) + OK_REPLY
                    4 -> random.nextBytes(16)
                    5 -> bytes(0x00, random.nextInt(256)) + random.nextBytes(random.nextInt(0, 16))
                    6 -> bytes(0x00) + random.nextBytes(4)
                    else -> OK_REPLY
                }
            }
            val found = when (seed % 3) {
                0 -> isoDep(tag)
                1 -> nfcA(tag)
                else -> nfcV(tag)
            }
            val vendorQueries = seed / 3 % 2 == 1

            val result = try {
                probeDisplay(found, vendorQueries, TEST_VENDOR_QUERIES)
            } catch (e: Exception) {
                fail("Seed $seed: the probe threw $e")
            }

            assertTrue(tag.sent.size <= MAX_PROBE_EXCHANGES, "Seed $seed: ${tag.sent.size} commands")
            when (seed % 3) {
                1 -> assertTrue(tag.reads().size <= MAX_TYPE2_READS, "Seed $seed: ${tag.reads().size} Type 2 reads")
                2 -> assertTrue(tag.sent.count { it[1] == 0x20.toByte() } <= MAX_NFCV_BLOCKS, "Seed $seed: too many block reads")
            }
            assertEquals(tag.sent.size, result.exchanges.size, "Seed $seed")
            val passes = result.exchanges.map { it.pass }
            assertEquals(passes.sortedBy { it.ordinal }, passes, "Seed $seed: a vendor query came before a documented read")
            assertTrue(vendorQueries || ProbePass.VENDOR !in passes, "Seed $seed: vendor queries while off")
            assertTrue(result.exchanges.all { (it.pass == ProbePass.VENDOR) == isVendorQuery(it.command, TEST_VENDOR_QUERIES) }, "Seed $seed")
            assertEquals(1, tag.closes, "Seed $seed")
        }
    }
}

private val OK_REPLY = bytes(0x90, 0x00)
