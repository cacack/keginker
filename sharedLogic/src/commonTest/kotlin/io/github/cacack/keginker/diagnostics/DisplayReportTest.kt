package io.github.cacack.keginker.diagnostics

import io.github.cacack.keginker.ezsign.SimulatedEzSignTag
import io.github.cacack.keginker.ezsign.TestAnswer
import io.github.cacack.keginker.ezsign.TestWriteOutcome
import io.github.cacack.keginker.ezsign.TestWriteRecord
import io.github.cacack.keginker.ezsign.nextDeviceInfoReply
import io.github.cacack.keginker.ezsign.testWriteFamilyFor
import io.github.cacack.keginker.image.forEachSeed
import io.github.cacack.keginker.nfc.TagDetail
import io.github.cacack.keginker.nfc.TagInfo
import io.github.cacack.keginker.nfc.TagKind
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.nfc.classifyIsoDepTag
import io.github.cacack.keginker.nfc.classifyNfcATag
import io.github.cacack.keginker.nfc.classifyNfcVTag
import io.github.cacack.keginker.probe.DEVICE_INFO
import io.github.cacack.keginker.probe.EZ_SIGN_29_DEVICE_INFO
import io.github.cacack.keginker.probe.EZ_SIGN_42_DEVICE_INFO
import io.github.cacack.keginker.probe.EZ_SIGN_DEVICE_INFO
import io.github.cacack.keginker.probe.EZ_SIGN_PROTOCOL
import io.github.cacack.keginker.probe.MADE_UP_ID
import io.github.cacack.keginker.probe.MADE_UP_SERIAL
import io.github.cacack.keginker.probe.NFCV_UID
import io.github.cacack.keginker.probe.NTAG213_VERSION
import io.github.cacack.keginker.probe.NfcVTag
import io.github.cacack.keginker.probe.ProbeExchange
import io.github.cacack.keginker.probe.ProbePass
import io.github.cacack.keginker.probe.ProbeResult
import io.github.cacack.keginker.probe.ProbeStep
import io.github.cacack.keginker.probe.TEST_VENDOR_QUERIES
import io.github.cacack.keginker.probe.Type2Tag
import io.github.cacack.keginker.probe.Type4Tag
import io.github.cacack.keginker.probe.launcherRecord
import io.github.cacack.keginker.probe.ndefTlv
import io.github.cacack.keginker.probe.probeDisplay
import io.github.cacack.keginker.probe.type2Memory
import io.github.cacack.keginker.probe.type5Memory
import io.github.cacack.keginker.waveshare.FailureKind
import io.github.cacack.keginker.waveshare.WriteResult
import io.github.cacack.keginker.waveshare.WriteStep
import io.github.cacack.keginker.waveshare.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private val APP = AppInfo(version = "1.4.0", buildType = "release", commit = "abc1234")
private val DEVICE = DeviceInfo(manufacturer = "Google", model = "Pixel 8", os = "Android 15", nfcEnabled = true)
private val UID = bytes(0x04, 0xA2, 0x3F, 0x11, 0x22, 0x33, 0x44)

private val SERIAL = "SEAA000282".encodeToByteArray()

/**
 * Fails if [text] holds [uid] in either byte order, or, in an exchange line
 * or the Memory section, its first or last 4 bytes, as when the UID is split
 * across replies. (A UID marker shows only 3.)
 */
private fun assertNoUidInExchangesOrMemory(text: String, uid: ByteArray) {
    assertNoFullUid(text, uid)
    val halves = listOf(uid.copyOfRange(0, 4), uid.copyOfRange(uid.size - 4, uid.size))
    val lines = text.lines().filter { "  > " in it || it.startsWith("    ") }
    for (half in halves + halves.map { it.reversedArray() }) {
        lines.forEach { assertFalse(half.toHex() in it, "\"${half.toHex()}\" in \"$it\" of:\n$text") }
    }
}

/** A probe result whose only exchange is the vendor pass's [DEVICE_INFO], answered with [reply]. */
private fun deviceInfoResult(reply: ByteArray, vendorPassSkipped: String? = null) = ProbeResult(
    tag = null,
    protocol = "unsupported",
    kind = TagKind.ISO_DEP,
    acceptedAids = emptyList(),
    capabilityContainer = null,
    ndef = null,
    note = null,
    exchanges = listOf(ProbeExchange(0, ProbeStep.VENDOR_QUERY, DEVICE_INFO, reply, null, ProbePass.VENDOR)),
    vendorQueriesListed = 1,
    vendorQueriesSent = 1,
    vendorPassSkipped = vendorPassSkipped,
)

private val OK = bytes(0x90, 0x00)

/** A probe, with vendor queries on, of an unsupported ISO-DEP tag that answers [DEVICE_INFO] with [reply]. */
private suspend fun probeEzSign(reply: ByteArray): ProbeResult {
    val tag = Type4Tag(launcherRecord("x"))
    val ezSign = object : Transport by tag {
        override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray =
            if (command.contentEquals(DEVICE_INFO)) reply else tag.transceive(command, timeoutMs)
    }
    return probeDisplay(classifyIsoDepTag(TagInfo(listOf("IsoDep", "NfcA"), UID)) { ezSign }, vendorQueries = true)
}

/** Fails if [text] holds all of [serial] or of [id]. */
private fun assertNoDeviceId(text: String, serial: ByteArray, id: ByteArray) {
    assertFalse(serial.toHex() in text, text)
    assertFalse(serial.decodeToString() in text, text)
    assertFalse(id.toHex() in text, text)
}

/** Fails unless [text] is free of [uid] in both byte orders. */
private fun assertNoFullUid(text: String, uid: ByteArray) {
    assertFalse(uid.toHex() in text, text)
    assertFalse(uid.reversedArray().toHex() in text, text)
}

class DisplayReportTest {
    @Test
    fun type4ReportListsWhatTheProbeLearned() = runTest {
        val info = TagInfo(listOf("IsoDep", "NfcA"), UID, bytes(0x44, 0x00), 0x20, bytes(0x80, 0x01))
        val result = probeDisplay(classifyIsoDepTag(info) { Type4Tag(launcherRecord("com.example.epaper")) })

        val text = formatDisplayReport(APP, DEVICE, result)

        listOf(
            "KegInker display report",
            "leaves your phone only if you share it",
            "Version: 1.4.0", "Model: Pixel 8",
            "Technologies: IsoDep, NfcA", "UID: 04 A2 3F … (7 bytes)", "ATQA: 44 00", "SAK: 20", "Historical bytes: 80 01",
            "Detected protocol: unsupported",
            "Probe (read-only)",
            "Applications accepted: D2 76 00 00 85 01 01",
            "Capability container: 00 0F 20",
            "NDEF: 1 record",
            "1. Android app: com.example.epaper",
            "SELECT_AID",
            "> 00 A4 04 00 07 D2 76 00 00 85 01 01 00  < 90 00",
            "READ_NDEF",
        ).forEach { assertTrue(it in text, "Missing \"$it\" in:\n$text") }
    }

    @Test
    fun type2ReportNeverShowsTheFullUid() = runTest {
        val memory = type2Memory(ndefTlv(launcherRecord("com.example.vendor")))
        val uid = memory.copyOfRange(0, 3) + memory.copyOfRange(4, 8)
        val result = probeDisplay(classifyNfcATag(TagInfo(listOf("NfcA"), uid)) { Type2Tag(memory) })

        val text = formatDisplayReport(APP, DEVICE, result)

        assertFalse(uid.toHex() in text, text)
        assertFalse(uid.copyOfRange(3, 7).toHex() in text, text)
        assertFalse("Applications accepted" in text, text)
        assertTrue("1. Android app: com.example.vendor" in text, text)
    }

    @Test
    fun reportShowsTheTagDetailsTheOsReported() = runTest {
        val info = TagInfo(listOf("NfcV"), NFCV_UID.reversedArray(), details = listOf(TagDetail("DSFID", "00"), TagDetail("Max transceive", "253 bytes")))
        val result = probeDisplay(classifyNfcVTag(info) { NfcVTag(type5Memory(ByteArray(0))) })

        val text = formatDisplayReport(APP, DEVICE, result)

        assertTrue("  ATQA: unavailable\n  SAK: unavailable\n  DSFID: 00\n  Max transceive: 253 bytes\n" in text, text)
    }

    @Test
    fun nfcVReportShowsTheSystemInformationAndMemory() = runTest {
        val result = probeDisplay(classifyNfcVTag(TagInfo(listOf("NfcV"), NFCV_UID.reversedArray())) { NfcVTag(type5Memory(ndefTlv(launcherRecord("com.example.epaper")))) })

        val text = formatDisplayReport(APP, DEVICE, result)

        listOf(
            "System information: DSFID 00, AFI 00, 28 blocks of 4 bytes, IC reference 01",
            "Capability container: E1 40 0E 01",
            "Memory (112 bytes)",
            "    0000  E1 40 0E 01 03",
            "    0060  00 00",
            "1. Android app: com.example.epaper",
            "Vendor queries: off",
            "documented GET_SYSTEM_INFO",
            "documented READ_BLOCKS",
        ).forEach { assertTrue(it in text, "Missing \"$it\" in:\n$text") }
    }

    @Test
    fun nfcVReportNeverShowsTheFullUid() = runTest {
        // The UID also turns up in user memory, in both byte orders.
        val memory = type5Memory(NFCV_UID + NFCV_UID.reversedArray())
        val result = probeDisplay(classifyNfcVTag(TagInfo(listOf("NfcV"), NFCV_UID.reversedArray())) { NfcVTag(memory) })

        val text = formatDisplayReport(APP, DEVICE, result)

        assertNoUidInExchangesOrMemory(text, NFCV_UID)
        assertTrue("< 00 0F [UID 78 56 34 … (8 bytes)] 00 00 1B 03 01" in text, text)
    }

    @Test
    fun nfcVMemoryReadsNeverShowAUidSplitAcrossBlocks() = runTest {
        // Each copy of the 8-byte UID spans three 4-byte blocks.
        val memory = type5Memory(bytes(0x00, 0x00) + NFCV_UID + bytes(0x00, 0x00, 0x00) + NFCV_UID.reversedArray())
        val result = probeDisplay(classifyNfcVTag(TagInfo(listOf("NfcV"), NFCV_UID.reversedArray())) { NfcVTag(memory) })

        val text = formatDisplayReport(APP, DEVICE, result)

        assertNoUidInExchangesOrMemory(text, NFCV_UID)
        assertTrue("documented READ_BLOCKS" in text && "< 00 + 4 data bytes (see Memory)" in text, text)
        assertTrue("    0000  E1 40 0E 01 00 00 [UID 78 56 34 … (8 bytes)] 00 00\n    0010  00 [UID 78 56 34 … (8 bytes)]" in text, text)
    }

    @Test
    fun type2MemoryReadsNeverShowAUidSplitAcrossReads() = runTest {
        // A proprietary TLV before the message holds the UID across the first two reads, and reversed across the next two.
        val tlv = bytes(0xFD, 31) + ByteArray(8) + UID + ByteArray(9) + UID.reversedArray()
        val result = probeDisplay(classifyNfcATag(TagInfo(listOf("NfcA"), UID)) { Type2Tag(type2Memory(tlv + ndefTlv(launcherRecord("x")))) })

        val text = formatDisplayReport(APP, DEVICE, result)

        assertNoUidInExchangesOrMemory(text, UID)
        assertTrue("documented READ_PAGES" in text && "< 16 data bytes (see Memory)" in text, text)
        assertTrue("  Memory (" in text && "    000C  E1 10 3E 00" in text, text)
        assertTrue("1. Android app: x" in text, text)
    }

    @Test
    fun aSystemInformationReplyHidesItsUidEvenWithoutTagDetails() {
        val reply = bytes(0x00, 0x0F) + NFCV_UID.reversedArray() + bytes(0x00, 0x00, 0x1B, 0x03, 0x01)
        val result = ProbeResult(
            tag = null,
            protocol = "unsupported",
            kind = TagKind.NFC_V,
            acceptedAids = emptyList(),
            capabilityContainer = null,
            ndef = null,
            note = null,
            exchanges = listOf(ProbeExchange(0, ProbeStep.GET_SYSTEM_INFO, bytes(0x02, 0x2B), reply, null, ProbePass.DOCUMENTED)),
        )

        val text = formatDisplayReport(APP, DEVICE, result)

        assertNoFullUid(text, NFCV_UID)
        assertTrue("< 00 0F [UID E0 04 01 … (8 bytes)] 00 00 1B 03 01" in text, text)
    }

    @Test
    fun noLineShowsMoreOfTheUidThanTheUidLine() = runTest {
        // Made-up UID, in historical bytes shaped like an EZ Sign tag's.
        val uid = bytes(0x3C, 0x5D, 0x7E, 0x9F)
        val info = TagInfo(listOf("IsoDep", "NfcA"), uid, bytes(0x44, 0x00), 0x20, bytes(0x90, 0x3C, 0x5D, 0x7E, 0x1D, 0x03))
        val result = probeDisplay(classifyIsoDepTag(info) { Type4Tag(launcherRecord("x")) })

        val text = formatDisplayReport(APP, DEVICE, result)

        assertTrue("UID: 3C 5D … (4 bytes)" in text, text)
        assertTrue("Historical bytes: 90 [UID 3C 5D … (4 bytes)] 1D 03" in text, text)
        val threeBytes = listOf(uid.copyOfRange(0, 3), uid.reversedArray().copyOfRange(0, 3)).map { it.toHex() }
        text.lines().forEach { line -> threeBytes.forEach { assertFalse(it in line, "\"$it\" in \"$line\" of:\n$text") } }
    }

    @Test
    fun type2ReportDecodesTheNxpIdentity() = runTest {
        val signature = ByteArray(32) { it.toByte() }
        val result = probeDisplay(classifyNfcATag(TagInfo(listOf("NfcA"), UID)) { Type2Tag(type2Memory(ndefTlv(launcherRecord("x"))), NTAG213_VERSION, signature) })

        val text = formatDisplayReport(APP, DEVICE, result)

        listOf(
            "Product version: 00 04 04 02 01 00 0F 03 (vendor 04, type 04, subtype 02, version 1.0, storage 0F: 128 to 256 bytes)",
            "Signature: 00 01 02 … (32 bytes)",
            "documented GET_VERSION",
            "documented READ_SIGNATURE",
            "< [signature 00 01 02 … (32 bytes)]",
        ).forEach { assertTrue(it in text, "Missing \"$it\" in:\n$text") }
        // The signature identifies one chip, so only its start is shown.
        assertFalse(signature.copyOfRange(2, 32).toHex() in text, text)
    }

    @Test
    fun reportLabelsTheVendorPass() = runTest {
        val memory = type2Memory(ndefTlv(launcherRecord("x")))
        val on = probeDisplay(classifyNfcATag(TagInfo(listOf("NfcA"), UID)) { Type2Tag(memory, vendorQueries = TEST_VENDOR_QUERIES) }, true, TEST_VENDOR_QUERIES)
        val none = probeDisplay(classifyNfcATag(TagInfo(listOf("NfcA"), UID)) { Type2Tag(memory) }, true, emptyList())

        val onText = formatDisplayReport(APP, DEVICE, on)

        assertTrue("Vendor queries: on, 1 listed, 1 sent" in onText, onText)
        assertTrue("vendor VENDOR_QUERY" in onText && "> F0 DF 00 00 01  < 00" in onText, onText)
        assertTrue("Vendor queries: on, none listed for this kind of tag" in formatDisplayReport(APP, DEVICE, none))
    }

    @Test
    fun reportShortensTheDeviceSerialAndIdInTheDeviceInformationReply() = runTest {
        val info = TagInfo(listOf("IsoDep", "NfcA"), UID)
        val result = probeDisplay(classifyIsoDepTag(info) { Type4Tag(launcherRecord("x"), deviceInfo = EZ_SIGN_DEVICE_INFO) }, vendorQueries = true)

        val text = formatDisplayReport(APP, DEVICE, result)

        val serial = "SEAA000282".encodeToByteArray()
        assertTrue("Vendor queries: on, 1 listed, 1 sent" in text, text)
        assertTrue(
            "< A0 07 00 07 14 02 58 01 90 A1 07 01 00 00 00 00 00 00 C0 0A [C0 53 45 41 … (10 bytes)] C1 04 [C1 12 34 … (4 bytes)] 90 00" in text,
            text,
        )
        assertFalse(serial.toHex() in text, text)
        assertFalse("12 34 AB CD" in text, text)
    }

    @Test
    fun reportSaysWhyTheVendorPassWasSkipped() {
        val result = deviceInfoResult(bytes(0x6A, 0x82), vendorPassSkipped = "contact with the tag was lost")

        val text = formatDisplayReport(APP, DEVICE, result)

        assertTrue("Vendor queries: on, skipped: contact with the tag was lost" in text, text)
    }

    @Test
    fun aDeviceInformationReplyWithoutAStatusWordStillHidesTheSerial() {
        val text = formatDisplayReport(APP, DEVICE, deviceInfoResult(EZ_SIGN_DEVICE_INFO))

        assertTrue("C0 0A [C0 53 45 41 … (10 bytes)] C1 04 [C1 12 34 … (4 bytes)]\n" in text, text)
        assertFalse(SERIAL.toHex() in text, text)
    }

    @Test
    fun aDeviceInformationReplyThatDoesNotParseIsHiddenWhole() {
        // C0 claims 0x20 bytes, past the end of the data.
        val reply = bytes(0xA0, 0x01, 0x00, 0xC0, 0x20) + SERIAL + bytes(0x90, 0x00)

        val text = formatDisplayReport(APP, DEVICE, deviceInfoResult(reply))

        assertTrue("< [15 bytes hidden] 90 00" in text, text)
        assertFalse(SERIAL.copyOfRange(0, 4).toHex() in text, text)
    }

    @Test
    fun aDeviceInformationReplyWithoutASerialOrIdIsShownInFull() {
        val text = formatDisplayReport(APP, DEVICE, deviceInfoResult(bytes(0xA0, 0x02, 0x01, 0x02, 0x90, 0x00)))

        assertTrue("< A0 02 01 02 90 00" in text, text)
    }

    @Test
    fun anEzSignReportDecodesAPortraitPanel() = runTest {
        val text = formatDisplayReport(APP, DEVICE, probeEzSign(EZ_SIGN_29_DEVICE_INFO + OK))

        listOf(
            "Detected protocol: $EZ_SIGN_PROTOCOL",
            "\nEZ Sign device information\n" +
                "  Resolution: 296 × 128 (native 128 × 296)\n" +
                "  Colours: 4 (black, white, yellow, red), mode 07\n" +
                "  Rows per block: 32\n" +
                "  A1: 01 12 00 30 FF FF FF\n" +
                "  B1: 1A\n" +
                "  B2: 14\n" +
                "  B3: 00\n" +
                "  D1: 01 20 00 00 00 00 00\n" +
                "  Decoded from: alt-core/nfc-eink https://github.com/alt-core/nfc-eink/blob/" +
                "682519f87aa6e422930d0f7a178eaa2d0d9640da/src/nfc_eink/device.py\n",
        ).forEach { assertTrue(it in text, "Missing \"$it\" in:\n$text") }
        assertNoDeviceId(text, MADE_UP_SERIAL, MADE_UP_ID)
    }

    @Test
    fun anEzSignReportDecodesALandscapePanel() = runTest {
        val text = formatDisplayReport(APP, DEVICE, probeEzSign(EZ_SIGN_42_DEVICE_INFO + OK))

        assertTrue("  Resolution: 400 × 300\n  Colours: 4 (black, white, yellow, red), mode 07\n" in text, text)
        assertTrue("  B1: 08\n" in text, text)
        assertNoDeviceId(text, MADE_UP_SERIAL, MADE_UP_ID)
    }

    @Test
    fun aReplyWithDataThatDoesNotDecodeGetsANoteAndARefusalNone() = runTest {
        val unknownMode = probeEzSign(bytes(0xA0, 0x07, 0xF0, 0x99, 0x20, 0x02, 0x58, 0x01, 0x90) + OK)
        val refused = probeEzSign(bytes(0x6D, 0x00))

        val unknownText = formatDisplayReport(APP, DEVICE, unknownMode)
        val refusedText = formatDisplayReport(APP, DEVICE, refused)

        assertTrue("Note: The EZ Sign device information didn't decode: colour mode 99 isn't one alt-core/nfc-eink knows." in unknownText, unknownText)
        // Any Type 4 tag that isn't EZ Sign may refuse the query.
        assertFalse("Note:" in refusedText, refusedText)
        listOf(unknownText, refusedText).forEach {
            assertTrue("Detected protocol: unsupported" in it, it)
            assertFalse("EZ Sign device information\n" in it, it)
        }
    }

    @Test
    fun randomDeviceInformationRepliesNeverShowTheSerialOrId() = runTest {
        forEachSeed { random ->
            val serial = random.nextBytes(10)
            val id = random.nextBytes(4)
            val reply = random.nextDeviceInfoReply(serial, id)

            val text = formatDisplayReport(APP, DEVICE, probeEzSign(reply))

            assertNoDeviceId(text, serial, id)
        }
    }

    @Test
    fun aReportWithoutTestWritesHasNoTestWritesSection() = runTest {
        val result = probeEzSign(EZ_SIGN_29_DEVICE_INFO + OK)

        val text = formatDisplayReport(APP, DEVICE, result, emptyList())

        assertEquals(formatDisplayReport(APP, DEVICE, result), text)
        assertFalse("Test writes" in text, text)
    }

    @Test
    fun theTestWritesSectionRecordsAnswersAndTimings() = runTest {
        val result = probeEzSign(EZ_SIGN_29_DEVICE_INFO + OK)
        val info = assertNotNull(result.deviceInfo)
        val family = assertNotNull(testWriteFamilyFor(result))
        val corner = family.patterns.first()
        val written = family.write(SimulatedEzSignTag(info), corner, ExchangeLog())
        val answered = TestWriteRecord(
            corner.title,
            written,
            corner.questions.map { TestAnswer(it.id, it.text, it.options.first()) },
        )
        val unanswered = TestWriteRecord("Colour bands", TestWriteOutcome(ExchangeLog(), refreshMs = 24_173, failure = null), emptyList())
        val failure = WriteResult.Failure(WriteStep.SEND_DATA, FailureKind.LOST_CONTACT, "Tag was lost")
        val failed = TestWriteRecord("Colour bands", TestWriteOutcome(ExchangeLog(), null, failure), emptyList())
        val retriedUnasked = TestWriteRecord("Colour bands", TestWriteOutcome(ExchangeLog(), null, failure), emptyList(), retry = true)
        val retried = TestWriteRecord(
            "Colour bands",
            TestWriteOutcome(ExchangeLog(), refreshMs = 24_173, failure = null),
            emptyList(),
            retry = true,
            positionChanged = "Not sure",
        )

        val text = formatDisplayReport(APP, DEVICE, result, listOf(answered, unanswered, failed, retriedUnasked, retried))

        listOf(
            "\nTest writes (5)\n\n1. Corner and line\n",
            "  Q: Hold the display the way up you'd read it. Which way is its long edge?\n  A: Across: it's wider than it's tall\n",
            "  Q: Where is the black square?\n  A: Top left\n",
            "  Q: Which way does the line from it run?\n  A: Along the top or bottom edge\n",
            "  Refresh: ${written.refreshMs} ms\n",
            "bytes image data",
            "\n2. Colour bands\n  Not answered\n  Refresh: 24173 ms\nExchanges: none\n",
            "\n3. Colour bands\n  Failed: SEND_DATA (sending the image): Tag was lost\nExchanges: none\n",
            "\n4. Colour bands\n  Position changed: not answered\n  Failed:",
            "\n5. Colour bands\n  Position changed: not sure\n  Not answered\n",
        ).forEach { assertTrue(it in text, "Missing \"$it\" in:\n$text") }
        assertTrue("Exchanges (${written.log.entries.size})" in text, text)
    }

    @Test
    fun randomTestWritesNeverShowTheUidOrImageData() = runTest {
        val result = probeEzSign(EZ_SIGN_29_DEVICE_INFO + OK)
        forEachSeed { random ->
            val image = random.nextBytes(16)
            val records = List(random.nextInt(1, 4)) {
                val log = ExchangeLog()
                repeat(random.nextInt(0, 6)) { at ->
                    val header = random.nextBytes(random.nextInt(0, 8))
                    val chunk = if (random.nextBoolean()) image else ByteArray(0)
                    // A reply that holds the UID somewhere, in either byte order.
                    val uid = if (random.nextBoolean()) UID else UID.reversedArray()
                    val reply = random.nextBytes(random.nextInt(0, 4)) + uid + random.nextBytes(random.nextInt(0, 4))
                    val error = if (random.nextInt(4) == 0) IllegalStateException("Reply ${uid.toHex()}") else null
                    log.record(at.toLong(), WriteStep.SEND_DATA, header + chunk, chunk.size, reply.takeIf { error == null }, error)
                }
                val failure = if (random.nextBoolean()) {
                    WriteResult.Failure(WriteStep.REFRESH, FailureKind.UNEXPECTED_REPLY, "Unexpected reply ${UID.toHex()} 90 00")
                } else {
                    null
                }
                val refreshMs = if (failure == null) random.nextLong(0, 15_000) else null
                TestWriteRecord("Pattern $it", TestWriteOutcome(log, refreshMs, failure), emptyList())
            }

            val text = formatDisplayReport(APP, DEVICE, result, records)

            assertNoUidInExchangesOrMemory(text, UID)
            assertFalse(image.toHex() in text, text)
        }
    }

    @Test
    fun aProbeThatStoppedSaysWhy() = runTest {
        val result = probeDisplay(classifyIsoDepTag(TagInfo(listOf("IsoDep"), UID)) { Type4Tag(ByteArray(0), hasNdefApp = false) })

        val text = formatDisplayReport(APP, DEVICE, result)

        assertTrue("Applications accepted: none" in text, text)
        assertTrue("NDEF: none found" in text, text)
        assertTrue("Note: The tag accepted none of the listed applications." in text, text)
    }

    @Test
    fun issueLinkCarriesTheReportWhenItFits() {
        val link = displayReportIssueLink("Line 1\nUID: 04 A2 … & more")

        assertTrue(link.includesReport)
        assertTrue(link.url.startsWith("https://github.com/cacack/keginker/issues/new?template=display-report.yml&report="))
        assertTrue(link.url.endsWith("Line%201%0AUID%3A%2004%20A2%20%E2%80%A6%20%26%20more"), link.url)
    }

    @Test
    fun issueLinkLeavesOutAReportTooLongToFit() {
        val link = displayReportIssueLink("x".repeat(MAX_ISSUE_URL_LENGTH))

        assertFalse(link.includesReport)
        assertEquals("https://github.com/cacack/keginker/issues/new?template=display-report.yml", link.url)
    }

    @Test
    fun shareSendsAFileOnlyWhenTheLinkLeavesTheReportOut() {
        assertFalse(displayReportIssueLink("short").sharesReportAsFile)
        assertTrue(displayReportIssueLink("x".repeat(MAX_ISSUE_URL_LENGTH)).sharesReportAsFile)
    }

    @Test
    fun issueLinkCaptionSaysIssuesArePublic() {
        listOf(displayReportIssueLink("short"), displayReportIssueLink("x".repeat(MAX_ISSUE_URL_LENGTH))).forEach {
            assertTrue("GitHub issues are public" in it.caption, it.caption)
        }
    }

    @Test
    fun percentEncodingLeavesOnlyUnreservedCharacters() {
        val encoded = percentEncode((0..0x7F).map(Int::toChar).joinToString("") + "é…")

        assertTrue(encoded.all { it.isLetterOrDigit() || it in "-._~%" }, encoded)
        assertTrue(encoded.endsWith("%C3%A9%E2%80%A6"), encoded)
    }
}
