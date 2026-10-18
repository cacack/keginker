package io.github.cacack.keginker

import io.github.cacack.keginker.image.GrayImage
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.ImageWriter
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.waveshare.WavesharePanel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val PANEL = WavesharePanel.EPD_2IN7

/** Random action sequences the model-based test runs, seeded 0, 1, 2 … */
private const val SEQUENCES = 1_000
private const val STEPS_PER_SEQUENCE = 30

/**
 * A tag of either kind that takes 1 ms of virtual time per command (none if
 * [instant]), records what it's sent, and drops out at command [failAt].
 */
private class ScriptedTag(
    val isoDep: Boolean,
    private val failAt: Int = Int.MAX_VALUE,
    private val instant: Boolean = false,
) : Transport {
    val sent = mutableListOf<List<Byte>>()
    var dropped = false

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        if (!instant) delay(1)
        if (sent.size >= failAt) {
            dropped = true
            throw IllegalStateException("Tag was lost")
        }
        sent += command.toList()
        return if (isoDep) {
            if (command[1] == 0x9B.toByte()) byteArrayOf(0x00, 0x90.toByte(), 0x00) else byteArrayOf(0x90.toByte(), 0x00)
        } else {
            if (command[1] == 0x0A.toByte()) byteArrayOf(0xFF.toByte(), 0x00) else byteArrayOf(0x00, 0x00)
        }
    }

    override fun close() {}

    fun found(): FoundTag = if (isoDep) FoundTag.WaveshareIsoDep(this) else FoundTag.Waveshare(this)
}

private val imageWriter = ImageWriter(pause = {})

private fun session() = WriteSession(PANEL, imageWriter)

/** The commands a tag of [isoDep]'s kind receives when [view] is written to it. */
private suspend fun commandsFor(view: BooleanArray, isoDep: Boolean): List<List<Byte>> {
    val tag = ScriptedTag(isoDep, instant = true)
    imageWriter.write(tag.found() as FoundTag.Writable, PANEL, view) {}
    return tag.sent
}

/** A small random image; the session scales it to the panel. */
private fun Random.nextImage() = GrayImage(8, 6, nextBytes(8 * 6))

/** How often the random sequences reached the situations worth checking. */
private class Coverage {
    var done = 0
    var failedMidWrite = 0
    var loadsDuringWrite = 0
    var changesDuringWait = 0
}

class WriteSessionTest {
    @Test
    fun imageLoadedDuringAWriteIsShownOnlyAfterItEnds() = runTest {
        val session = session()
        val written = session.view.value
        session.write()
        val tag = ScriptedTag(isoDep = false)
        launch { session.onTag(tag.found()) }
        runCurrent()
        assertIs<WriteState.Writing>(session.controller.state.value)

        session.imageLoaded(GrayImage(1, 1, byteArrayOf(0)))

        assertContentEquals(written, session.view.value)
        advanceUntilIdle()
        assertEquals(commandsFor(written, isoDep = false), tag.sent)
        // The new image is shown, so "Done" (which was about the old one) is cleared.
        assertTrue(session.view.value.none { it })
        assertEquals(WriteState.Idle, session.controller.state.value)
    }

    @Test
    fun ditherAndThresholdAreIgnoredDuringAWrite() = runTest {
        val session = session()
        val written = session.view.value
        session.write()
        launch { session.onTag(ScriptedTag(isoDep = true).found()) }
        runCurrent()

        session.toggleDither()
        session.setThreshold(10)

        assertContentEquals(written, session.view.value)
        advanceUntilIdle()
        assertEquals(WriteState.Done, session.controller.state.value)
    }

    /**
     * Drives a session through random sequences of user actions and tag
     * events, checking after every step that Done always means the tag got
     * the preview, a changed preview never leaves an older view armed, and a
     * handled tag never leaves the state Writing.
     */
    @Test
    fun previewAndWrittenLabelNeverDisagree() {
        val coverage = Coverage()
        for (seed in 0 until SEQUENCES) {
            val steps = mutableListOf<String>()
            try {
                runTest { runSequence(Random(seed), steps, coverage) }
            } catch (e: Throwable) {
                throw AssertionError("Failed for seed $seed after ${steps.joinToString()}: $e", e)
            }
        }
        // The sequences must reach the interleavings the invariant is about.
        assertTrue(coverage.done > SEQUENCES / 10, "Done ${coverage.done} times")
        assertTrue(coverage.failedMidWrite > SEQUENCES / 10, "Failed mid-write ${coverage.failedMidWrite} times")
        assertTrue(coverage.loadsDuringWrite > SEQUENCES / 10, "Loaded during a write ${coverage.loadsDuringWrite} times")
        assertTrue(coverage.changesDuringWait > SEQUENCES / 10, "Changed while waiting ${coverage.changesDuringWait} times")
    }

    private suspend fun TestScope.runSequence(random: Random, steps: MutableList<String>, coverage: Coverage) {
        val session = session()
        val state = session.controller.state
        val tags = mutableListOf<ScriptedTag>()
        val tagJobs = mutableListOf<Job>()
        // The view the controller holds, tracked from outside: arming is ignored mid-write.
        var armed: BooleanArray? = null

        repeat(STEPS_PER_SEQUENCE) {
            when (random.nextInt(8)) {
                0 -> {
                    steps += "toggle dither"
                    if (state.value == WriteState.Waiting) coverage.changesDuringWait++
                    session.toggleDither()
                }
                1 -> {
                    val level = random.nextInt(-10, 266)
                    steps += "threshold $level"
                    session.setThreshold(level)
                }
                2 -> {
                    steps += "image loaded"
                    when (state.value) {
                        is WriteState.Writing -> coverage.loadsDuringWrite++
                        WriteState.Waiting -> coverage.changesDuringWait++
                        else -> {}
                    }
                    session.imageLoaded(random.nextImage())
                }
                3 -> {
                    // Also the Retry button after a failure.
                    steps += "write"
                    if (state.value !is WriteState.Writing) armed = session.view.value
                    session.write()
                }
                4 -> {
                    steps += "cancel"
                    session.controller.cancel()
                }
                5 -> {
                    if (random.nextInt(10) == 0) {
                        steps += "unsupported tag"
                        tagJobs += launch { session.onTag(FoundTag.Unsupported(byteArrayOf(4, 1, 2, 3, 4, 5, 6))) }
                    } else {
                        val isoDep = random.nextBoolean()
                        // Half the tags drop out somewhere in the write.
                        val failAt = if (random.nextBoolean()) random.nextInt(0, 80) else Int.MAX_VALUE
                        steps += "${if (isoDep) "Rev2.2" else "classic"} tag failing at $failAt"
                        val tag = ScriptedTag(isoDep, failAt)
                        tags += tag
                        tagJobs += launch { session.onTag(tag.found()) }
                    }
                }
                else -> {
                    val ms = random.nextLong(1, 60)
                    steps += "${ms}ms pass"
                    advanceTimeBy(ms)
                }
            }
            runCurrent()

            val current = state.value
            if (current == WriteState.Done) {
                coverage.done++
                // Only one write runs at a time, so the last tag that was sent anything gave this Done.
                val written = tags.last { it.sent.isNotEmpty() }
                assertEquals(commandsFor(session.view.value, written.isoDep), written.sent, "Done, but the tag got another image")
            }
            if (current == WriteState.Waiting || current is WriteState.Writing) {
                assertContentEquals(armed, session.view.value, "The preview changed but an older view is still armed")
            }
            if (tagJobs.all { it.isCompleted }) {
                assertTrue(current !is WriteState.Writing, "Every tag was handled, but the state is still Writing")
            }
        }
        advanceUntilIdle()
        coverage.failedMidWrite += tags.count { it.dropped && it.sent.isNotEmpty() }
    }
}
