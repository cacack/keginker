package io.github.cacack.keginker.diagnostics

import io.github.cacack.keginker.LabelDisplay
import io.github.cacack.keginker.image.forEachSeed
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.TagInfo
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.waveshare.REPLY_SHOWN_BYTES
import io.github.cacack.keginker.waveshare.WriteStep
import io.github.cacack.keginker.waveshare.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private val APP = AppInfo(version = "1.4.0", buildType = "release", commit = "abc1234")
private val DEVICE = DeviceInfo(manufacturer = "Google", model = "Pixel 8", os = "Android 15", nfcEnabled = true)
private val TAG = TagInfo(
    technologies = listOf("IsoDep", "NfcA"),
    uid = bytes(0x04, 0xA2, 0x3F, 0x11, 0x22, 0x33, 0x44),
    atqa = bytes(0x44, 0x00),
    sak = 0x20,
    historicalBytes = bytes(0x59, 0x5A, 0x52, 0x43, 0x4F, 0x53, 0x03, 0x00),
)

/** The historical-bytes line of a report on a tag with [uid] and [historical]. */
private fun historicalLine(uid: ByteArray, historical: ByteArray) =
    formatDiagnosticReport(APP, DEVICE, WriteReport(TagInfo(listOf("IsoDep"), uid, historicalBytes = historical), "unsupported", "EPD_2IN7", "x", Outcome.NOT_ATTEMPTED, testPattern = false))
        .lines().single { it.trim().startsWith("Historical bytes:") }.trim()

private object NoTransport : Transport {
    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray = error("unused")
}

private val OK = bytes(0x90, 0x00)
private val BUSY = bytes(0x01, 0x90, 0x00)

private fun exchange(
    atMs: Long,
    step: WriteStep,
    command: ByteArray,
    reply: ByteArray? = OK,
    imageBytes: Int = 0,
    error: String? = null,
    durationMs: Long = 8,
) = Exchange(atMs, step, command, imageBytes, if (error == null) reply else null, error, durationMs)

private fun failedWrite(exchanges: List<Exchange>) = WriteReport(
    tag = TAG,
    protocol = "Waveshare Rev2.2 (ISO-DEP)",
    panel = "EPD_2IN7",
    message = "Lost contact with the tag while sending the image.",
    outcome = Outcome.FAILED,
    testPattern = false,
    step = WriteStep.SEND_DATA,
    reason = "Tag communication failed at chunk 2/27",
    cause = IllegalStateException("Tag was lost"),
    exchanges = exchanges,
)

class DiagnosticReportTest {
    @Test
    fun containsEveryField() {
        val text = formatDiagnosticReport(APP, DEVICE, failedWrite(emptyList()))

        listOf(
            "KegInker diagnostics report",
            "leaves your phone only if you share it",
            "Version: 1.4.0", "Build: release", "Commit: abc1234",
            "Manufacturer: Google", "Model: Pixel 8", "OS: Android 15", "NFC: on",
            "Technologies: IsoDep, NfcA",
            "ATQA: 44 00", "SAK: 20", "Historical bytes: 59 5A 52 43 4F 53 03 00",
            "Detected protocol: Waveshare Rev2.2 (ISO-DEP)",
            "Panel: EPD_2IN7",
            "Message: Lost contact with the tag while sending the image.",
            "Step: SEND_DATA (sending the image)",
            "Reason: Tag communication failed at chunk 2/27",
            "Cause: IllegalStateException: Tag was lost",
            "Image: chosen image (not included)",
            "Outcome: failed",
        ).forEach { assertTrue(it in text, "missing \"$it\" in:\n$text") }
    }

    @Test
    fun aSuccessfulWriteSaysSoAndNamesTheTestPattern() {
        val report = WriteReport(TAG, "Waveshare classic (NFC-A)", "EPD_2IN7", "Done.", Outcome.SUCCEEDED, testPattern = true)

        val text = formatDiagnosticReport(APP, DEVICE, report)

        assertTrue("Outcome: succeeded" in text, text)
        assertTrue("Image: built-in test pattern" in text, text)
        assertFalse("Step:" in text, text)
    }

    @Test
    fun marksTagFieldsThePlatformWithholdsAsUnavailable() {
        // As iOS reports a MIFARE tag: no ATQA or SAK.
        val tag = TagInfo(listOf("MIFARE (Ultralight)"), bytes(0x57, 0x53, 0x44, 0x5A, 0x31, 0x30, 0x6D))
        val report = WriteReport(tag, "Waveshare classic (NFC-A)", "EPD_2IN7", "x", Outcome.FAILED, testPattern = false)

        val text = formatDiagnosticReport(APP, DEVICE, report)

        assertTrue("Technologies: MIFARE (Ultralight)" in text, text)
        assertTrue("ATQA: unavailable" in text, text)
        assertTrue("SAK: unavailable" in text, text)
        assertFalse("Historical bytes" in text, text)
    }

    @Test
    fun writeReportTakesTheTagsInfoAndProtocol() {
        val tag = FoundTag.WaveshareIsoDep(NoTransport, TAG)

        val report = writeReport(tag, LabelDisplay.WAVESHARE_2IN7, "Done.", Outcome.SUCCEEDED, testPattern = true, log = null)

        assertSame(TAG, report.tag)
        assertEquals("Waveshare Rev2.2 (ISO-DEP)", report.protocol)
        assertEquals("WAVESHARE_2IN7", report.panel)
        assertEquals(Outcome.SUCCEEDED, report.outcome)
        assertTrue(report.testPattern)
        assertTrue(report.exchanges.isEmpty())
    }

    @Test
    fun shortensTheUid() {
        val text = formatDiagnosticReport(APP, DEVICE, failedWrite(emptyList()))

        assertTrue("UID: 04 A2 3F … (7 bytes)" in text, text)
        assertFalse("11 22 33 44" in text, text)
    }

    @Test
    fun showsAtMostHalfOfAShortUid() {
        fun uidLine(uid: ByteArray) =
            formatDiagnosticReport(APP, DEVICE, WriteReport(TagInfo(listOf("NfcA"), uid), "unsupported", "EPD_2IN7", "x", Outcome.NOT_ATTEMPTED, testPattern = false))
                .lines().single { it.trim().startsWith("UID:") }.trim()

        assertEquals("UID: 08 1F … (4 bytes)", uidLine(bytes(0x08, 0x1F, 0x2E, 0x3D)))
        assertEquals("UID: (1 bytes)", uidLine(bytes(0x08)))
        assertEquals("UID: (0 bytes)", uidLine(ByteArray(0)))
    }

    @Test
    fun hidesUidBytesInsideTheHistoricalBytes() {
        // Made-up UID; EZ Sign tags put its first three bytes in their historical bytes.
        val uid = bytes(0x3C, 0x5D, 0x7E, 0x9F)
        fun historicalLine(historical: ByteArray) = historicalLine(uid, historical)

        assertEquals("Historical bytes: 90 [UID 3C 5D … (4 bytes)] 1D 03", historicalLine(bytes(0x90, 0x3C, 0x5D, 0x7E, 0x1D, 0x03)))
        assertEquals("Historical bytes: [UID 3C 5D … (4 bytes)] 00", historicalLine(bytes(0x3C, 0x5D, 0x7E, 0x9F, 0x00)))
        // Least significant byte first.
        assertEquals("Historical bytes: 80 [UID 3C 5D … (4 bytes)]", historicalLine(bytes(0x80, 0x9F, 0x7E, 0x5D)))
        // No more than the UID line shows.
        assertEquals("Historical bytes: 3C 5D 01 7E 9F", historicalLine(bytes(0x3C, 0x5D, 0x01, 0x7E, 0x9F)))
    }

    @Test
    fun hidesAWindowFromTheMiddleOfTheUidInsideTheHistoricalBytes() {
        val uid = TAG.uid

        // 3F 11 22 33: bytes 2 to 5 of the UID, more than the 3 the UID line shows.
        assertEquals("Historical bytes: 80 [UID 04 A2 3F … (7 bytes)] 00", historicalLine(uid, bytes(0x80, 0x3F, 0x11, 0x22, 0x33, 0x00)))
        // The same, least significant byte first.
        assertEquals("Historical bytes: 80 [UID 04 A2 3F … (7 bytes)] 00", historicalLine(uid, bytes(0x80, 0x33, 0x22, 0x11, 0x3F, 0x00)))
        // No more than the UID line shows.
        assertEquals("Historical bytes: 80 11 22 33 00", historicalLine(uid, bytes(0x80, 0x11, 0x22, 0x33, 0x00)))
        // An OS name that shares no bytes with the UID stays as it is.
        assertEquals("Historical bytes: 59 5A 52 43 4F 53 03 00", historicalLine(uid, TAG.historicalBytes!!))
    }

    @Test
    fun noRunOfTheUidLongerThanTheUidLineSurvivesInTheHistoricalBytes() = forEachSeed { random ->
        val uid = random.nextBytes(listOf(4, 7, 10).random(random))
        val orders = listOf(uid, uid.reversedArray())
        // Random bytes, with windows of the UID from anywhere in it, in either order, between them.
        var historical = ByteArray(0)
        repeat(random.nextInt(1, 5)) {
            val order = orders.random(random)
            val from = random.nextInt(order.size)
            historical += random.nextBytes(random.nextInt(0, 4)) + order.copyOfRange(from, random.nextInt(from + 1, order.size + 1))
        }

        val line = historicalLine(uid, historical)

        val longest = uidShownBytes(uid) + 1
        for (order in orders) {
            for (from in 0..order.size - longest) {
                val window = order.copyOfRange(from, from + longest).toHex()
                assertFalse(window in line, "\"$window\" in \"$line\" for UID ${uid.toHex()}")
            }
        }
    }

    @Test
    fun listsEachExchangeWithItsTimeStepAndOutcome() {
        val text = formatDiagnosticReport(
            APP, DEVICE,
            failedWrite(
                listOf(
                    exchange(0, WriteStep.UNLOCK, bytes(0x74, 0xB1, 0x00, 0x00)),
                    exchange(1250, WriteStep.SEND_DATA, bytes(0x74, 0x9A, 0x00, 0x0E, 0xFA), imageBytes = 250),
                    exchange(1300, WriteStep.SEND_DATA, bytes(0x74, 0x9A, 0x00, 0x0E, 0xFA), imageBytes = 250, error = "IllegalStateException: Tag was lost", durationMs = 19_850),
                ),
            ),
        )

        val lines = text.lines().dropWhile { !it.startsWith("Exchanges") }
        assertEquals(
            listOf(
                "Exchanges (3)",
                "     0 ms  UNLOCK     > 74 B1 00 00  < 90 00",
                "  1250 ms  SEND_DATA  > 74 9A 00 0E FA … +250 bytes image data  < 90 00",
                "  1300 ms  SEND_DATA  > 74 9A 00 0E FA … +250 bytes image data  ! IllegalStateException: Tag was lost (after 19850 ms)",
            ),
            lines.filter { it.isNotEmpty() },
        )
    }

    @Test
    fun aFailureOrASlowExchangeShowsHowLongItTookAndAQuickOneDoesNot() {
        // A failure's time tells a tag that stopped at once from one a platform limit cut off (#138).
        val text = formatDiagnosticReport(
            APP, DEVICE,
            failedWrite(
                listOf(
                    exchange(0, WriteStep.UNLOCK, bytes(0x74, 0xB1, 0x00, 0x00), durationMs = SLOW_EXCHANGE_MS - 1),
                    exchange(10, WriteStep.REFRESH, bytes(0x74, 0x9A), durationMs = 24_173),
                    exchange(24_200, WriteStep.WAIT_FOR_REFRESH, bytes(0x74, 0x9B), error = "IllegalStateException: no response", durationMs = 3),
                ),
            ),
        )

        assertTrue("> 74 B1 00 00  < 90 00\n" in text, text)
        assertTrue("< 90 00 (after 24173 ms)\n" in text, text)
        assertTrue("! IllegalStateException: no response (after 3 ms)\n" in text, text)
    }

    @Test
    fun theLogTimesEachExchangeFromItsCommandToItsReply() {
        val clock = TestTimeSource()
        val log = ExchangeLog(clock)
        clock += 100.milliseconds
        val sentAt = log.elapsedMs()
        clock += 24_173.milliseconds

        log.record(sentAt, WriteStep.REFRESH, bytes(0xF0, 0xD4), imageBytes = 0, reply = null, error = IllegalStateException("no response"))

        assertEquals(24_173, log.entries.single().durationMs)
    }

    @Test
    fun collapsesRepeatedPolls() {
        val poll = bytes(0x74, 0x9B, 0x00, 0x0F, 0x01)
        val polls = List(5) { exchange(9000L + it * 200, WriteStep.WAIT_FOR_REFRESH, poll, reply = BUSY) }

        val text = formatDiagnosticReport(
            APP, DEVICE,
            failedWrite(polls + exchange(10000, WriteStep.WAIT_FOR_REFRESH, poll, error = "IllegalStateException: Tag was lost")),
        )

        assertTrue(" 9000 ms  WAIT_FOR_REFRESH  > 74 9B 00 0F 01  < 01 90 00 × 5" in text, text)
        assertTrue("10000 ms  WAIT_FOR_REFRESH  > 74 9B 00 0F 01  ! IllegalStateException: Tag was lost" in text, text)
        assertEquals(2, text.lines().count { "WAIT_FOR_REFRESH  >" in it })
    }

    @Test
    fun unsupportedTagReportHasNoStepOrExchanges() {
        val report = WriteReport(
            tag = TagInfo(listOf("NfcA", "MifareUltralight"), bytes(0x04, 0xA1, 0xB2, 0xC3, 0xD4, 0xE5, 0xF6), bytes(0x44, 0x00), 0x00),
            protocol = "unsupported",
            panel = "EPD_2IN7",
            message = "This tag isn't supported.",
            outcome = Outcome.NOT_ATTEMPTED,
            testPattern = true,
        )

        val text = formatDiagnosticReport(APP, DEVICE.copy(nfcEnabled = false), report)

        assertTrue("Detected protocol: unsupported" in text, text)
        assertTrue("Exchanges: none" in text, text)
        assertTrue("NFC: off" in text, text)
        assertTrue("Outcome: not attempted" in text, text)
        assertFalse("Image:" in text, text)
        assertFalse("Step:" in text || "Reason:" in text || "Cause:" in text || "Historical bytes:" in text, text)
        assertFalse("null" in text, text)
    }

    @Test
    fun isPlainText() {
        val text = formatDiagnosticReport(APP, DEVICE, failedWrite(listOf(exchange(0, WriteStep.UNLOCK, bytes(0x74, 0xB1)))))

        assertTrue(text.all { it.code < 128 || it == '…' || it == '×' }, text)
        assertFalse(text.lines().any { it.startsWith("#") || it.startsWith("```") }, text)
    }

    @Test
    fun cutsAnOversizedReply() {
        val text = formatDiagnosticReport(APP, DEVICE, failedWrite(listOf(exchange(0, WriteStep.UNLOCK, bytes(0x74, 0xB1), reply = ByteArray(5_000)))))

        val line = text.lines().single { "UNLOCK" in it }
        assertTrue(line.endsWith("… (5000 bytes)"), line)
        assertEquals(REPLY_SHOWN_BYTES, line.substringAfter("< ").substringBefore(" …").split(" ").size, line)
    }

    @Test
    fun showsTheLongestRequestedReplyInFull() {
        // A 250-byte READ BINARY and its status word.
        val text = formatDiagnosticReport(APP, DEVICE, failedWrite(listOf(exchange(0, WriteStep.UNLOCK, bytes(0x74, 0xB1), reply = ByteArray(252)))))

        assertFalse("bytes)" in text.lines().single { "UNLOCK" in it }, text)
    }
}
