package io.github.cacack.keginker

import io.github.cacack.keginker.image.ColorImage
import io.github.cacack.keginker.image.GrayImage
import io.github.cacack.keginker.image.WHITE
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

/** A uniform image of one gray. */
private fun plain(gray: Int) = ColorImage.fromGray(GrayImage(1, 1, byteArrayOf(gray.toByte())))

/** Loading a picked image on Android: the latest pick wins, and a failed load says so. Routing found tags to test writes. */
@OptIn(ExperimentalCoroutinesApi::class)
class WriteViewModelTest {
    private val main = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(main)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun laterPickWinsOverASlowerEarlierOne() = runTest(main) {
        val model = WriteViewModel()
        val earlierRead = CompletableDeferred<Unit>()
        // The earlier, slow pick finishes reading only after the later one has shown,
        // like a blocking read that cancelling can't interrupt.
        val earlier = model.load { withContext(NonCancellable) { earlierRead.await() }; plain(0) }
        runCurrent()
        model.load { plain(255) }.join()
        earlierRead.complete(Unit)
        earlier.join()

        assertTrue(model.view.value.all { it == WHITE }, "the later, white pick is shown")
        assertEquals(null, model.loadError.value)
    }

    @Test
    fun aFailedLoadSaysTheImageCouldNotBeOpened() = runTest(main) {
        val model = WriteViewModel()
        model.load { throw IOException("Image is over $MAX_PIXELS pixels") }.join()

        assertEquals("Couldn't open that image.", model.loadError.value)
    }

    @Test
    fun aWaitingTestWriteTakesTheTagAheadOfTheProbe() = runTest(main) {
        val model = WriteViewModel()
        val display = EzSignTag()
        model.reportDisplay(vendorQueries = true)
        model.onTag(foundEzSign(display))
        advanceUntilIdle()
        val result = (model.probe.state.value as ProbeState.Done).result
        val testWrites = assertNotNull(testWriteControllerFor(result))
        model.offerTestWrites(testWrites)
        testWrites.optIn()
        model.probe.arm()

        model.onTag(foundEzSign(display))
        advanceUntilIdle()

        assertIs<TestWriteState.Asking>(testWrites.state.value)
        assertEquals(ProbeState.Waiting, model.probe.state.value)
    }

    @Test
    fun closingTheReportDropsItsTestWrites() = runTest(main) {
        val model = WriteViewModel()
        model.reportDisplay(vendorQueries = true)
        model.onTag(foundEzSign(EzSignTag()))
        advanceUntilIdle()
        val testWrites = assertNotNull(testWriteControllerFor((model.probe.state.value as ProbeState.Done).result))
        model.offerTestWrites(testWrites)
        testWrites.optIn()

        model.closeProbe()

        assertNull(model.testWrites.value)
        assertIs<TestWriteState.Done>(testWrites.state.value)
    }
}
