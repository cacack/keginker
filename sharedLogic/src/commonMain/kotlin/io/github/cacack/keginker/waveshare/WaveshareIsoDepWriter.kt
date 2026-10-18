/*
 * Derived from Magic ePaper's lib/waveshare/services/waveshare_nfc_protocol.dart
 * (_writeIsoDepDisplay; https://github.com/fossasia/magic-epaper-app, commit
 * 7c04b55), licensed under the Apache License, Version 2.0.
 *
 * Modified: ported from Dart to Kotlin; register values derived from the 2.7"
 * panel instead of 200×200; the frame is packed in view rows; failures are
 * reported as a typed result naming the failing step; the busy poll requires
 * `90 00`, and losing the tag while waiting for refresh is a failure rather
 * than a success.
 */
package io.github.cacack.keginker.waveshare

import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.nfc.Transport
import kotlinx.coroutines.delay

/**
 * Writes a frame to a Waveshare Rev2.2 board (ISO-DEP, YZRCOS chip) over
 * [transport]. These boards drive the panel controller directly: CLA `0x74`
 * APDUs write controller registers and RAM, and every reply ends in `90 00`.
 */
class WaveshareIsoDepWriter(
    private val transport: Transport,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** Where to record each exchange, for a diagnostics report. */
    private val log: ExchangeLog? = null,
) {
    /** For Swift, which can't supply the suspending [pause]. */
    constructor(transport: Transport) : this(transport, { delay(it) })

    /**
     * Writes [frame] (the whole controller RAM, from [packControllerFrame]).
     * Succeeds only once the panel reports that the refresh has finished.
     *
     * [onProgress] receives 0–100 and never decreases; it keeps rising while
     * the panel refreshes after the image is sent at 90.
     */
    @Throws(Exception::class)
    suspend fun write(
        panel: WavesharePanel,
        frame: ByteArray,
        onProgress: (Int) -> Unit = {},
    ): WriteResult {
        require(frame.size == panel.controllerFrameSize) {
            "Frame is ${frame.size} bytes; ${panel.name} needs ${panel.controllerFrameSize}"
        }
        val xEnd = panel.viewWidth / 8 - 1
        val yMax = panel.controllerRows - 1
        val yLow = yMax and 0xFF
        val yHigh = yMax shr 8
        return runSteps {
            sendOk(WriteStep.UNLOCK, apdu(0xB1, 0x00, 0x00, UNLOCK_KEY.size, *UNLOCK_KEY))
            sendOk(WriteStep.RESET, apdu(0x97, 0x00, 0x08, LE_ANY))
            pause(RESET_PAUSE_MS)
            sendOk(WriteStep.RESET, apdu(0x97, 0x01, 0x08, LE_ANY))
            pause(RESET_PAUSE_MS)

            // Gate lines, then RAM written X increasing, Y decreasing from the top row.
            setRegister(WriteStep.CONFIGURE, DRIVER_OUTPUT, yLow, yHigh, 0x01)
            setRegister(WriteStep.CONFIGURE, DATA_ENTRY_MODE, 0x01)
            setRegister(WriteStep.CONFIGURE, RAM_X_WINDOW, 0x00, xEnd)
            setRegister(WriteStep.CONFIGURE, RAM_Y_WINDOW, yLow, yHigh, 0x00, 0x00)
            setRegister(WriteStep.CONFIGURE, BORDER_WAVEFORM, 0x01)
            setRegister(WriteStep.CONFIGURE, TEMPERATURE_SENSOR, 0x80)
            setRegister(WriteStep.CONFIGURE, RAM_X_COUNTER, 0x00)
            setRegister(WriteStep.CONFIGURE, RAM_Y_COUNTER, yLow, yHigh)
            pause(CONFIGURE_PAUSE_MS)

            selectRegister(WriteStep.SEND_DATA, WRITE_BLACK_WHITE_RAM)
            val chunks = (frame.size + CHUNK_SIZE - 1) / CHUNK_SIZE
            for (chunk in 0 until chunks) {
                val start = chunk * CHUNK_SIZE
                val data = frame.copyOfRange(start, minOf(start + CHUNK_SIZE, frame.size))
                transport.exchangeImageOk(
                    WriteStep.SEND_DATA,
                    writeDataHeader(data.size),
                    data,
                    ::isOk,
                    "chunk ${chunk + 1}/$chunks",
                    log,
                )
                onProgress((chunk + 1) * DATA_PROGRESS / chunks)
            }

            setRegister(WriteStep.REFRESH, DISPLAY_UPDATE_CONTROL, 0xF7)
            selectRegister(WriteStep.REFRESH, MASTER_ACTIVATION)
            // The panel takes a few seconds to redraw; show that time passing.
            for (second in 1..REFRESH_WAIT_SECONDS) {
                pause(1000)
                onProgress(DATA_PROGRESS + second)
            }
            awaitRefresh(maxPolls(chunks))
            onProgress(100)
        }
    }

    private suspend fun awaitRefresh(maxPolls: Int) {
        repeat(maxPolls) {
            val reply = transport.exchange(WriteStep.WAIT_FOR_REFRESH, apdu(0x9B, 0x00, 0x0F, 0x01), log = log)
            if (isOk(reply) && reply.size >= 3 && reply[0] != BUSY) return
            pause(POLL_PAUSE_MS)
        }
        throw StepFailed(WriteStep.WAIT_FOR_REFRESH, FailureKind.REFRESH_UNCONFIRMED, "Refresh not confirmed after $maxPolls polls")
    }

    /** Busy polls left in the write budget once [chunks] of image data and everything else are sent. */
    private fun maxPolls(chunks: Int) = busyPollLimit(
        pausesMs = 2 * RESET_PAUSE_MS + CONFIGURE_PAUSE_MS + REFRESH_WAIT_SECONDS * 1000L,
        commands = SETUP_COMMANDS + chunks + REFRESH_COMMANDS,
        pollPauseMs = POLL_PAUSE_MS,
    )

    private suspend fun selectRegister(step: WriteStep, register: Int) =
        sendOk(step, apdu(0x99, 0x00, 0x0D, 0x01, register))

    private suspend fun setRegister(step: WriteStep, register: Int, vararg values: Int) {
        selectRegister(step, register)
        sendOk(step, writeData(ByteArray(values.size) { values[it].toByte() }))
    }

    private suspend fun sendOk(step: WriteStep, command: ByteArray) =
        transport.exchangeOk(step, command, ::isOk, log = log)

    private companion object {
        const val DATA_PROGRESS = IMAGE_SENT_PROGRESS
        const val CHUNK_SIZE = 250
        const val REFRESH_WAIT_SECONDS = 6
        const val RESET_PAUSE_MS = 150L
        const val CONFIGURE_PAUSE_MS = 100L
        const val POLL_PAUSE_MS = 200L

        // Counted by hand from write(); WriteBudgetTest fails if they drift from what it sends.

        /** Commands before the image data: unlock, 2 resets, 8 register writes of 2, select RAM. */
        const val SETUP_COMMANDS = 1 + 2 + 8 * 2 + 1

        /** Commands after the image data, before polling: update control (2), activate. */
        const val REFRESH_COMMANDS = 3
        const val CLA = 0x74
        const val LE_ANY = 0x00
        const val BUSY: Byte = 0x01

        /** Fixed key the vendor app unlocks the chip with. */
        val UNLOCK_KEY = intArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77)

        // SSD16xx-style controller registers.
        const val DRIVER_OUTPUT = 0x01
        const val DATA_ENTRY_MODE = 0x11
        const val MASTER_ACTIVATION = 0x20
        const val DISPLAY_UPDATE_CONTROL = 0x22
        const val WRITE_BLACK_WHITE_RAM = 0x24
        const val TEMPERATURE_SENSOR = 0x18
        const val BORDER_WAVEFORM = 0x3C
        const val RAM_X_WINDOW = 0x44
        const val RAM_Y_WINDOW = 0x45
        const val RAM_X_COUNTER = 0x4E
        const val RAM_Y_COUNTER = 0x4F

        /** `CLA INS P1 P2`, then [rest] as given: either `Le`, or `Lc` followed by data. */
        fun apdu(ins: Int, p1: Int, p2: Int, vararg rest: Int): ByteArray {
            val header = intArrayOf(CLA, ins, p1, p2)
            return ByteArray(header.size + rest.size) { i -> (if (i < 4) header[i] else rest[i - 4]).toByte() }
        }

        /** The `74 9A 00 0E Lc` header of a write of [size] data bytes. */
        fun writeDataHeader(size: Int): ByteArray = byteArrayOf(CLA.toByte(), 0x9A.toByte(), 0x00, 0x0E, size.toByte())

        fun writeData(data: ByteArray): ByteArray = writeDataHeader(data.size) + data

        fun isOk(reply: ByteArray) =
            reply.size >= 2 && reply[reply.size - 2] == 0x90.toByte() && reply[reply.size - 1] == 0x00.toByte()
    }
}
