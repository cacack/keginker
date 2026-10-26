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

    private fun failAWrite() {
        compose.setContent { App(tags, APP, DEVICE) }
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
        compose.setContent { App(tags, APP, DEVICE) }
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
        compose.onNodeWithText("Top left").performScrollTo().performClick()
        compose.onNodeWithText("Along the top or bottom edge").performScrollTo().performClick()
        compose.onNodeWithText("Next").performScrollTo().assertIsEnabled().performClick()
        waitForText("Display report")

        compose.onNodeWithText("Test writes (1)", substring = true).assertExists()
        compose.onNodeWithText("A: Top left", substring = true).assertExists()
        compose.onNodeWithText("Start test writes").assertDoesNotExist()
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
}
