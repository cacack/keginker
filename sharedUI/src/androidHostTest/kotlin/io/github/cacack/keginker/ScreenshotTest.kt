package io.github.cacack.keginker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.app.ActivityOptionsCompat
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.cacack.keginker.diagnostics.AppInfo
import io.github.cacack.keginker.diagnostics.DeviceInfo
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.SimulatedWaveshareTransport
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.waveshare.DONE_MESSAGE
import io.github.cacack.keginker.waveshare.IMAGE_SENT_PROGRESS
import io.github.cacack.keginker.waveshare.WavesharePanel
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The photo picker, answering every launch with [uri]. */
private class PickedPhoto(private val uri: Uri) : ActivityResultRegistryOwner {
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
        }
    }
}

/** A classic tag that accepts everything but holds the write once [chunks] image chunks are in. */
private class HeldTag(private val chunks: Int) : Transport {
    private val tag = SimulatedWaveshareTransport(commandDelayMs = 0)
    private var received = 0

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        if (command[1] == 0x19.toByte() && received++ == chunks) awaitCancellation()
        return tag.transceive(command, timeoutMs)
    }
}

private val APP = AppInfo(version = "1.0.0", buildType = "release", commit = "abc1234")
private val DEVICE = DeviceInfo(manufacturer = "Google", model = "Pixel 8", os = "Android 15", nfcEnabled = true)

/**
 * The README screenshots (`docs/images/screenshots/android.*.png`): the
 * sample label in `fixtures/screenshots`, chosen through the photo picker,
 * then written. NFC is available (no [App] `nfcProblem`). Records or checks
 * the images only when Roborazzi is asked to; see CONTRIBUTING.md.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// At 256 dpi (1.6x) the 220 dp preview scales the 176-pixel label by exactly 2;
// at a fractional scale, pixel rounding differs between macOS and Linux.
@Config(sdk = [35], qualifiers = "w360dp-h800dp-256dpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val tags = MutableSharedFlow<FoundTag>(extraBufferCapacity = 1)
    private val screenshots = File(System.getProperty("keginker.screenshots") ?: error("keginker.screenshots isn't set"))
    private val label = File(System.getProperty("keginker.sampleLabel") ?: error("keginker.sampleLabel isn't set"))

    private fun showSampleLabel() {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides PickedPhoto(Uri.fromFile(label))) {
                App(tags, APP, DEVICE, nfcProblem = null)
            }
        }
        val testPattern = previewPixels()
        // A click without a touch, which would leave a ripple mid-fade in the picture.
        compose.onNodeWithText("Choose image").performSemanticsAction(SemanticsActions.OnClick)
        // The image loads off the main thread.
        compose.waitUntil(timeoutMillis = 10_000) { !previewPixels().contentEquals(testPattern) }
    }

    private fun previewPixels(): IntArray =
        compose.onNodeWithContentDescription("Preview of the tag").captureToImage().toPixelMap().buffer

    private fun capture(name: String) = compose.onRoot().captureRoboImage(File(screenshots, name).path)

    private fun startWrite(tag: Transport) {
        compose.onNodeWithText("Write to tag").performScrollTo().performClick()
        compose.waitForIdle()
        tags.tryEmit(FoundTag.Waveshare(tag))
    }

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 30_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun preview() {
        showSampleLabel()
        capture("android.1-preview.png")
    }

    @Test
    fun writing() {
        showSampleLabel()
        val percent = 60
        startWrite(HeldTag(chunks = percent * WavesharePanel.EPD_2IN7.chunkCount / IMAGE_SENT_PROGRESS))
        waitForText("Writing $percent%")
        capture("android.2-writing.png")
    }

    @Test
    fun done() {
        showSampleLabel()
        startWrite(SimulatedWaveshareTransport(commandDelayMs = 0))
        waitForText(DONE_MESSAGE)
        capture("android.3-done.png")
    }
}
