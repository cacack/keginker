package io.github.cacack.keginker.waveshare

import io.github.cacack.keginker.nfc.Transport

// Test-only simulated panels. Each answers like its board and reconstructs the
// image the display would show from the commands it receives. They're written
// from docs/architecture/protocols.md, not from the packers or writers, so a
// check against them tests our code against our reading of the hardware.

/** A board that answers like the real one and rebuilds what its display shows. */
interface SimulatedPanel : Transport {
    /** The view the display shows after the last refresh, row-major; `null` pixels were never written. */
    val shown: List<Boolean?>?
}

private fun reply(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private fun Byte.u() = toInt() and 0xFF

/** Memory pixels, 1 bit per pixel, MSB first, 1 = white; `null` until written. */
private fun Array<Boolean?>.writeByte(pixel: Int, value: Byte) {
    for (bit in 0 until 8) this[pixel + bit] = value.u() and (0x80 ushr bit) != 0
}

/**
 * A Waveshare classic tag. Collects `CD 19` image data into the panel's
 * [WavesharePanel.width] × [WavesharePanel.height] memory and, on `CD 09`,
 * shows it in the panel's view, turned 180°: memory pixel (x, y) at view
 * pixel (width − 1 − x, height − 1 − y).
 */
class SimulatedClassicPanel(private val panel: WavesharePanel) : SimulatedPanel {
    private val memory = arrayOfNulls<Boolean>(panel.width * panel.height)
    private var written = 0
    private var dataStarted = false
    private var busyPolls = 0

    override var shown: List<Boolean?>? = null
        private set

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        if (command.size < 2 || command[0].u() != 0xCD) return ERROR
        when (command[1].u()) {
            0x00 -> if (command.size != 3 || command[2].u() != panel.typeCode) return ERROR
            0x18 -> dataStarted = true
            0x19 -> {
                val length = command.getOrNull(2)?.u() ?: return ERROR
                if (!dataStarted || command.size != 3 + length || written + length > memory.size / 8) return ERROR
                for (i in 0 until length) memory.writeByte((written + i) * 8, command[3 + i])
                written += length
            }
            0x09 -> {
                shown = List(memory.size) { i ->
                    val viewX = i % panel.viewWidth
                    val viewY = i / panel.viewWidth
                    when (panel.view) {
                        PanelView.TURNED_180 -> memory[(panel.height - 1 - viewY) * panel.width + (panel.width - 1 - viewX)]
                    }
                }
                busyPolls = 1
            }
            0x0A -> return if (busyPolls-- > 0) OK else reply(0xFF, 0x00)
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0D -> Unit
            else -> return ERROR
        }
        return OK
    }

    private companion object {
        val OK = reply(0x00, 0x00)
        val ERROR = reply(0x01, 0x00)
    }
}

/**
 * A Waveshare Rev2.2 board. Tracks the SSD16xx-style registers it's sent
 * (`0x01` gate count, `0x11` data entry mode, `0x44`/`0x45` RAM window,
 * `0x4E`/`0x4F` RAM cursor), writes `0x24` data through that addressing into a RAM of
 * [WavesharePanel.controllerRows] rows, and on `0x20` shows the highest
 * [WavesharePanel.viewHeight] rows, highest at the bottom, RAM column 0 at the right.
 */
class SimulatedRev22Panel(private val panel: WavesharePanel) : SimulatedPanel {
    private val rowBytes = panel.viewWidth / 8
    private val ram = arrayOfNulls<Boolean>(panel.controllerRows * panel.viewWidth)
    private var unlocked = false
    private var register = -1
    private var busyPolls = 0

    // Power-on defaults: X and Y increasing, X first, whole RAM.
    private var entryMode = 0x03
    private var xStart = 0
    private var xEnd = rowBytes - 1
    private var yStart = 0
    private var yEnd = panel.controllerRows - 1
    private var x = 0
    private var y = 0

    override var shown: List<Boolean?>? = null
        private set

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        if (command.size < 5 || command[0].u() != 0x74) return ERROR
        val ins = command[1].u()
        if (ins != 0xB1 && !unlocked) return ERROR
        when (ins) {
            0xB1 -> unlocked = command.drop(5) == UNLOCK_KEY
            0x97 -> if (command[2].u() == 0x01) reset()
            0x99 -> {
                if (command.size != 6) return ERROR
                register = command[5].u()
                if (register == 0x20) activate()
            }
            0x9A -> {
                val data = command.copyOfRange(5, command.size)
                if (data.size != command[4].u() || !writeRegister(data)) return ERROR
            }
            0x9B -> return if (busyPolls-- > 0) reply(0x01, 0x90, 0x00) else reply(0x00, 0x90, 0x00)
            else -> return ERROR
        }
        return OK
    }

    private fun reset() {
        entryMode = 0x03
        xStart = 0
        xEnd = rowBytes - 1
        yStart = 0
        yEnd = panel.controllerRows - 1
        x = 0
        y = 0
    }

    /** Applies [data] to the selected register; false if the controller would reject it. */
    private fun writeRegister(data: ByteArray): Boolean {
        fun word(i: Int) = data[i].u() or (data[i + 1].u() shl 8)
        when (register) {
            0x11 -> entryMode = data[0].u()
            0x44 -> { xStart = data[0].u(); xEnd = data[1].u() }
            0x45 -> { yStart = word(0); yEnd = word(2) }
            0x4E -> x = data[0].u()
            0x4F -> y = word(0)
            0x24 -> for (byte in data) if (!writeRam(byte)) return false
            // Gate count: the controller drives word(0) + 1 rows.
            0x01 -> if (data.size != 3 || word(0) + 1 != panel.controllerRows) return false
            0x18, 0x22, 0x3C -> Unit
            else -> return false
        }
        return true
    }

    /** Writes one byte at the cursor and advances it as the data entry mode says. */
    private fun writeRam(byte: Byte): Boolean {
        if (x !in 0 until rowBytes || y !in 0 until panel.controllerRows) return false
        ram.writeByte(y * panel.viewWidth + x * 8, byte)
        val xStep = if (entryMode and 0x01 != 0) 1 else -1
        val yStep = if (entryMode and 0x02 != 0) 1 else -1
        // Bit 2 clear: X moves first and Y steps when X wraps at the window's end.
        if (entryMode and 0x04 == 0) {
            if (x == xEnd) { x = xStart; y = if (y == yEnd) yStart else y + yStep } else x += xStep
        } else {
            if (y == yEnd) { y = yStart; x = if (x == xEnd) xStart else x + xStep } else y += yStep
        }
        return true
    }

    private fun activate() {
        val hidden = panel.controllerRows - panel.viewHeight
        shown = List(panel.viewWidth * panel.viewHeight) { i ->
            val viewX = i % panel.viewWidth
            val viewY = i / panel.viewWidth
            when (panel.view) {
                PanelView.TURNED_180 -> ram[(hidden + viewY) * panel.viewWidth + (panel.viewWidth - 1 - viewX)]
            }
        }
        busyPolls = 1
    }

    private companion object {
        val OK = reply(0x90, 0x00)
        val ERROR = reply(0x6F, 0x00)
        val UNLOCK_KEY = reply(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77).toList()
    }
}
