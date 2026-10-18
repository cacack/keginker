package io.github.cacack.keginker.waveshare

import io.github.cacack.keginker.image.forEachSeed
import io.github.cacack.keginker.image.testPattern
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.ImageWriter
import io.github.cacack.keginker.nfc.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** A board: how to simulate it and how [ImageWriter] finds it. */
private enum class Board(
    val simulate: (WavesharePanel) -> SimulatedPanel,
    val tag: (Transport) -> FoundTag.Writable,
) {
    CLASSIC(::SimulatedClassicPanel, FoundTag::Waveshare),
    REV22(::SimulatedRev22Panel, FoundTag::WaveshareIsoDep),
}

/** Writes [view] through [ImageWriter] to a simulated [board] and returns what its display shows. */
private suspend fun displayed(board: Board, panel: WavesharePanel, view: BooleanArray): List<Boolean?>? {
    val simulated = board.simulate(panel)
    val result = ImageWriter { }.write(board.tag(simulated), panel, view) {}
    assertEquals(WriteResult.Success, result, "$board ${panel.name}")
    return simulated.shown
}

/** Checks that every board and panel shows exactly the view [viewFor] makes. */
private suspend fun assertShowsExactly(viewFor: (WavesharePanel) -> BooleanArray) {
    for (panel in WavesharePanel.entries) {
        val view = viewFor(panel)
        for (board in Board.entries) {
            assertEquals(view.toList(), displayed(board, panel, view), "$board ${panel.name}")
        }
    }
}

private fun WavesharePanel.pixels() = viewWidth * viewHeight

private fun WavesharePanel.patternView() = renderView(this, testPattern(viewWidth, viewHeight))

class SimulatedPanelTest {
    @Test
    fun showsAllWhite() = runTest { assertShowsExactly { BooleanArray(it.pixels()) { true } } }

    @Test
    fun showsAllBlack() = runTest { assertShowsExactly { BooleanArray(it.pixels()) } }

    @Test
    fun showsTheTestPattern() = runTest { assertShowsExactly { it.patternView() } }

    @Test
    fun showsRandomViews() = runTest {
        forEachSeed { random -> assertShowsExactly { panel -> BooleanArray(panel.pixels()) { random.nextBoolean() } } }
    }

    @Test
    fun testPatternMarkerIsInTheTopLeftCorner() = runTest {
        for (panel in WavesharePanel.entries) {
            for (board in Board.entries) {
                val shown = displayed(board, panel, panel.patternView())!!
                val inset = maxOf(2, panel.viewWidth / 40) + 2 // just inside the border
                fun white(x: Int, y: Int) = shown[y * panel.viewWidth + x]
                assertEquals(false, white(inset, inset), "$board ${panel.name}: top-left")
                assertEquals(true, white(panel.viewWidth - 1 - inset, inset), "$board ${panel.name}: top-right")
            }
        }
    }

    @Test
    fun unwrittenPixelsShowAsMissing() = runTest {
        // A write that stops one chunk short leaves the end of memory unwritten.
        val panel = WavesharePanel.EPD_2IN7
        val classic = SimulatedClassicPanel(panel)
        val chunk = byteArrayOf(0xCD.toByte(), 0x19, panel.chunkSize.toByte()) + ByteArray(panel.chunkSize)
        classic.transceive(byteArrayOf(0xCD.toByte(), 0x18), 0)
        repeat(panel.chunkCount - 1) { classic.transceive(chunk, 0) }
        classic.transceive(byteArrayOf(0xCD.toByte(), 0x09), 0)

        val shown = classic.shown!!
        assertTrue(null in shown)
        assertFalse(shown.filterNotNull().any { it })
    }

    @Test
    fun rev22RejectsCommandsBeforeUnlock() = runTest {
        val reply = SimulatedRev22Panel(WavesharePanel.EPD_2IN7).transceive(byteArrayOf(0x74, 0x97.toByte(), 0x00, 0x08, 0x00), 0)
        assertNotEquals(listOf(0x90.toByte(), 0x00.toByte()), reply.toList())
    }
}
