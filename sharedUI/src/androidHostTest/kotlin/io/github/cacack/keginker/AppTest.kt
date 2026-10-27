package io.github.cacack.keginker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.cacack.keginker.diagnostics.AppInfo
import io.github.cacack.keginker.diagnostics.DeviceInfo
import androidx.test.core.app.ApplicationProvider
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.Transport
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import io.github.cacack.keginker.waveshare.DONE_MESSAGE

/** A classic tag that accepts every command and finishes refreshing at once. */
private class AcceptingTag : Transport {
    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray =
        if (command[1] == 0x0A.toByte()) byteArrayOf(0xFF.toByte(), 0x00) else byteArrayOf(0x00, 0x00)
}

/** A tag that records what it's sent and answers nothing useful. */
private class RecordingTag : Transport {
    val sent = mutableListOf<ByteArray>()

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        sent += command
        return byteArrayOf(0x00)
    }
}

/** A tag lifted away before the first command. */
private class LiftedTag : Transport {
    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray = error("Tag was lost")
}

private val APP = AppInfo(version = "1.0.0", buildType = "release", commit = "abc1234")
private val DEVICE = DeviceInfo(manufacturer = "Google", model = "Pixel 8", os = "Android 15", nfcEnabled = true)

/** CONSTITUTION.md C2 on Android: a failed write offers a retry. */
@RunWith(RobolectricTestRunner::class)
class AppTest {
    @get:Rule
    val compose = createComposeRule()

    private val tags = MutableSharedFlow<FoundTag>(extraBufferCapacity = 1)

    /** The title and text of each report [App] saves, as a Debug build does. */
    private val saved = mutableListOf<Pair<String, String>>()
    private val saveReport = { title: String, report: String -> saved += title to report }

    private fun failAWrite() {
        compose.setContent { App(tags, APP, DEVICE, saveReport = saveReport) }
        compose.onNodeWithText("Write to tag").performScrollTo().performClick()
        compose.waitForIdle()
        tags.tryEmit(FoundTag.Waveshare(LiftedTag()))
        compose.waitForIdle()
    }

    @Test
    fun failedWriteShowsWhatHappenedAndARetry() {
        failAWrite()

        compose.onNodeWithText("Lost contact with the tag", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Hold the back of the phone", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun shareDetailsShowsTheReportBeforeSharing() {
        failAWrite()

        compose.onNodeWithText("Share details").performScrollTo().performClick()

        compose.onNodeWithText("Diagnostics report").assertIsDisplayed()
        compose.onNodeWithText("Commit: abc1234", substring = true).assertExists()
        compose.onNodeWithText("Step: INIT", substring = true).assertExists()
    }

    @Test
    fun aDebugBuildSavesTheDiagnosticsReportItShows() {
        failAWrite()
        assertTrue("Saved before it was shown", saved.isEmpty())

        compose.onNodeWithText("Share details").performScrollTo().performClick()
        compose.waitForIdle()

        val (title, report) = saved.single()
        assertEquals("Diagnostics report", title)
        compose.onNodeWithText(report.lines().first { "Step:" in it }.trim(), substring = true).assertExists()
        assertTrue(report.startsWith("KegInker diagnostics report"))
    }

    private fun succeedAWrite() {
        compose.onNodeWithText("Write to tag").performScrollTo().performClick()
        compose.waitForIdle()
        tags.tryEmit(FoundTag.Waveshare(AcceptingTag()))
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(DONE_MESSAGE).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun setOfferReports(on: Boolean) {
        compose.onNodeWithText("Advanced").performClick()
        val toggle = compose.onNode(isToggleable())
        if (on) toggle.assertIsOff() else toggle.assertIsOn()
        toggle.performClick()
        compose.onNodeWithText("Back").performClick()
    }

    @Test
    fun successfulWriteOffersNoReportByDefault() {
        compose.setContent { App(tags, APP, DEVICE) }

        succeedAWrite()

        compose.onNodeWithText("Share details").assertDoesNotExist()
    }

    @Test
    fun withTheToggleOnASuccessfulWriteOffersItsReport() {
        compose.setContent { App(tags, APP, DEVICE) }
        setOfferReports(on = true)

        succeedAWrite()
        compose.onNodeWithText("Share details").performScrollTo().performClick()

        compose.onNodeWithText("Outcome: succeeded", substring = true).assertExists()
        compose.onNodeWithText("Image: built-in test pattern", substring = true).assertExists()
    }

    @Test
    fun theToggleIsSaved() {
        compose.setContent { App(tags, APP, DEVICE) }
        setOfferReports(on = true)

        assertTrue(ReportSetting(ApplicationProvider.getApplicationContext()).offerAfterSuccess)
    }

    @Test
    fun aSavedToggleIsOnAtStart() {
        ReportSetting(ApplicationProvider.getApplicationContext()).offerAfterSuccess = true
        compose.setContent { App(tags, APP, DEVICE) }

        compose.onNodeWithText("Advanced").performClick()

        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun reportADisplayDisarmsAWriteSoNoTagIsWritten() {
        compose.setContent { App(tags, APP, DEVICE) }
        compose.onNodeWithText("Write to tag").performScrollTo().performClick()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Report a display").performScrollTo().performClick()
        compose.waitForIdle()

        // The probe reads the first tag; the next one, held while its report shows, isn't written.
        tags.tryEmit(FoundTag.Waveshare(RecordingTag()))
        compose.waitForIdle()
        val next = RecordingTag()
        tags.tryEmit(FoundTag.Waveshare(next))
        compose.waitForIdle()

        assertTrue("A tag was written during Report a display", next.sent.isEmpty())
    }

    @Test
    fun reportADisplayOpensWithVendorCommandsOffAndWarned() {
        compose.setContent { App(tags, APP, DEVICE) }
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Report a display").performScrollTo().performClick()

        compose.onNodeWithText("These commands aren't documented. They could change what the display shows.").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()

        // Off again the next time the screen opens.
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Report a display").performScrollTo().performClick()
        compose.onNode(isToggleable()).assertIsOff()
    }

    @Test
    fun reportADisplayKeepsAFailedWritesReport() {
        failAWrite()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Report a display").performScrollTo().performClick()
        // Back out of the probe, then out of Advanced.
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Back").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Share details").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aFailedWriteOffersItsReportWithTheToggleOff() {
        failAWrite()

        compose.onNodeWithText("Share details").performScrollTo().assertIsDisplayed()
    }

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 30_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** Reports [display] with vendor commands on, then opens the report's test writes. */
    private fun offerTestWrites(display: EzSignTag) {
        compose.setContent { App(tags, APP, DEVICE, saveReport = saveReport) }
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Report a display").performScrollTo().performClick()
        compose.onNode(isToggleable()).performClick()
        compose.waitForIdle()
        tags.tryEmit(foundEzSign(display))
        waitForText("Display report")
        compose.onNodeWithText("Test writes").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun testWritesWriteNothingUntilConfirmed() {
        val display = EzSignTag()
        offerTestWrites(display)
        val probed = display.sent.size

        tags.tryEmit(foundEzSign(display))
        compose.waitForIdle()

        compose.onNodeWithText("Start test writes").assertIsDisplayed()
        assertEquals("Commands sent before the user confirmed", probed, display.sent.size)
    }

    @Test
    fun testWritesEndInTheReportWithTheAnswers() {
        val display = EzSignTag()
        offerTestWrites(display)
        compose.onNodeWithText("Start test writes").performClick()
        compose.onNodeWithText("Hold the display to the phone", substring = true).assertIsDisplayed()

        tags.tryEmit(foundEzSign(display))
        waitForText("Where is the black square?")
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Across: it's wider than it's tall").performScrollTo().performClick()
        compose.onNodeWithText("Top left").performScrollTo().performClick()
        compose.onNodeWithText("Along the top or bottom edge").performScrollTo().performClick()
        compose.onNodeWithText("Next").performScrollTo().assertIsEnabled().performClick()
        waitForText("Display report")

        compose.onNodeWithText("Test writes (1)", substring = true).assertExists()
        compose.onNodeWithText("A: Top left", substring = true).assertExists()
        compose.onNodeWithText("Start test writes").assertDoesNotExist()
    }

    @Test
    fun aRetryAfterAFailedTestWriteReportsWhetherThePhoneMoved() {
        val display = EzSignTag()
        offerTestWrites(display)
        compose.onNodeWithText("Start test writes").performClick()
        display.lostCommands = 1
        tags.tryEmit(foundEzSign(display))
        waitForText("Did you change how the phone sits on the display?")

        compose.onNodeWithText("Yes").performScrollTo().performClick()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        tags.tryEmit(foundEzSign(display))
        waitForText("Where is the black square?")
        compose.onNodeWithText("Back").performClick()
        waitForText("Display report")

        compose.onNodeWithText("Test writes (2)", substring = true).assertExists()
        compose.onNodeWithText("2. Corner and line\n  Position changed: yes\n", substring = true).assertExists()
    }

    @Test
    fun backingOutOfTestWritesKeepsTheRecordsSoFar() {
        val display = EzSignTag()
        offerTestWrites(display)
        compose.onNodeWithText("Start test writes").performClick()
        tags.tryEmit(foundEzSign(display))
        waitForText("Where is the black square?")

        compose.onNodeWithText("Back").performClick()
        waitForText("Display report")

        compose.onNodeWithText("Test writes (1)", substring = true).assertExists()
        compose.onNodeWithText("Not answered", substring = true).assertExists()
    }

    @Test
    fun backingOutOfTheConfirmationOffersTestWritesAgain() {
        offerTestWrites(EzSignTag())

        compose.onNodeWithText("Back").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Display report").assertIsDisplayed()
        compose.onNodeWithText("Test writes").performScrollTo().assertIsDisplayed()
    }

    /** Issue #140: a saved report is the shared text, so it hides the UID, serial and image as that does. */
    @Test
    fun aSavedDisplayReportHidesTheUidSerialAndImage() {
        val display = EzSignTag(serial = EZ_SIGN_SERIAL)
        offerTestWrites(display)
        compose.onNodeWithText("Start test writes").performClick()
        tags.tryEmit(foundEzSign(display))
        waitForText("Where is the black square?")
        compose.onNodeWithText("Back").performClick()
        waitForText("Display report")
        compose.waitForIdle()

        // Once after the probe, then again with the test write's record.
        assertEquals(listOf("Display report", "Display report"), saved.map { it.first })
        val report = saved.last().second
        assertTrue("Test write missing", "Test writes (1)" in report)
        // The UID and serial reached the report, shortened, so the checks below can fail.
        assertTrue("UID start missing", "UID: ${hex(EZ_SIGN_UID.copyOfRange(0, 3))} …" in report)
        assertTrue("Serial marker missing", "[C0 ${hex(EZ_SIGN_SERIAL.copyOfRange(0, 3))} …" in report)
        val image = display.sent.maxBy { it.size }
        val hidden = mapOf(
            "UID" to hex(EZ_SIGN_UID),
            "reversed UID" to hex(EZ_SIGN_UID.reversedArray()),
            "serial" to hex(EZ_SIGN_SERIAL),
            "serial text" to EZ_SIGN_SERIAL.decodeToString(),
            "image data" to hex(image),
        )
        for ((what, shown) in hidden) assertTrue("Saved report shows the $what", shown !in report)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }
}
