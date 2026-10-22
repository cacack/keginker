package io.github.cacack.keginker

import io.github.cacack.keginker.image.GrayImage
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

/** A uniform image of one grey. */
private fun plain(gray: Int) = GrayImage(1, 1, byteArrayOf(gray.toByte()))

/** Loading a picked image on Android: the latest pick wins, and a failed load says so. */
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

        assertTrue(model.view.value.all { it }, "the later, white pick is shown")
        assertEquals(null, model.loadError.value)
    }

    @Test
    fun aFailedLoadSaysTheImageCouldNotBeOpened() = runTest(main) {
        val model = WriteViewModel()
        model.load { throw IOException("Image is over $MAX_PIXELS pixels") }.join()

        assertEquals("Couldn't open that image.", model.loadError.value)
    }
}
