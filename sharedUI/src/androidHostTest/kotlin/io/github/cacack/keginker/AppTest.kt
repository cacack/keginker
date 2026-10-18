package io.github.cacack.keginker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.cacack.keginker.diagnostics.AppInfo
import io.github.cacack.keginker.diagnostics.DeviceInfo
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.Transport
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
