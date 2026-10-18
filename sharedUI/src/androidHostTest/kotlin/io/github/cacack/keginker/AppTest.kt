package io.github.cacack.keginker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

/** CONSTITUTION.md C2 on Android: a failed write offers a retry. */
@RunWith(RobolectricTestRunner::class)
class AppTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun failedWriteShowsWhatHappenedAndARetry() {
        val tags = MutableSharedFlow<FoundTag>(extraBufferCapacity = 1)
        compose.setContent { App(tags) }

        compose.onNodeWithText("Write to tag").performScrollTo().performClick()
        compose.waitForIdle()
        tags.tryEmit(FoundTag.Waveshare(LiftedTag()))
        compose.waitForIdle()

        compose.onNodeWithText("Lost contact with the tag", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Hold the back of the phone", substring = true).performScrollTo().assertIsDisplayed()
    }
}
