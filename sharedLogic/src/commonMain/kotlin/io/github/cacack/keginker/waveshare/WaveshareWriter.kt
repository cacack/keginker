/*
 * Derived from Magic ePaper's lib/waveshare/services/waveshare_nfc_protocol.dart
 * (https://github.com/fossasia/magic-epaper-app, commit 7c04b55),
 * licensed under the Apache License, Version 2.0.
 *
 * Modified: ported from Dart to Kotlin; limited to the 2.7" sequence; failures
 * are reported as a typed result naming the failing step; losing the tag while
 * waiting for refresh is a failure rather than a success.
 */
package io.github.cacack.keginker.waveshare

import io.github.cacack.keginker.nfc.Transport
import kotlinx.coroutines.delay

/** The stages of a Waveshare write, used to say where one failed. */
enum class WriteStep {
    INIT,
    SELECT_PANEL,
    POWER_ON,
    PREPARE_DATA,
    START_DATA,
    SEND_DATA,
    REFRESH,
    WAIT_FOR_REFRESH,
    POWER_OFF,

    /** Rev2.2 only: unlocking the chip. */
    UNLOCK,

    /** Rev2.2 only: resetting the panel controller. */
    RESET,

    /** Rev2.2 only: setting up the controller's RAM window. */
    CONFIGURE,
}

/** What went wrong in a failed write; it decides what the user is told. */
enum class FailureKind {
    /** Communication with the tag broke off. */
    LOST_CONTACT,

    /** The tag answered, but not with the reply the step needs. */
    UNEXPECTED_REPLY,

    /** The tag kept answering, but never reported the refresh finished. */
    REFRESH_UNCONFIRMED,
}

sealed interface WriteResult {
    data object Success : WriteResult

    data class Failure(
        val step: WriteStep,
        val kind: FailureKind,
        val reason: String,
        val cause: Throwable? = null,
    ) : WriteResult
}

/** Writes a packed frame to a Waveshare classic tag over [transport]. */
class WaveshareWriter(
    private val transport: Transport,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /** For Swift, which can't supply the suspending [pause]. */
    constructor(transport: Transport) : this(transport, { delay(it) })

    /**
     * Writes [frame] (1 bit per pixel, already rotated and packed for [panel]).
     * Succeeds only once the tag confirms the refresh has completed.
     *
     * [onProgress] receives 0–100 and never decreases.
     */
    @Throws(Exception::class)
    suspend fun write(
        panel: WavesharePanel,
        frame: ByteArray,
        onProgress: (Int) -> Unit = {},
    ): WriteResult {
        require(frame.size == panel.frameSize) {
            "Frame is ${frame.size} bytes; ${panel.name} needs ${panel.frameSize}"
        }
        return runSteps {
            sendOk(WriteStep.INIT, command(0xCD, 0x0D))
            sendOk(WriteStep.SELECT_PANEL, command(0xCD, 0x00, panel.typeCode))
            pause(SELECT_PAUSE_MS)
            for ((code, wait) in POWER_ON_SEQUENCE) {
                sendOk(WriteStep.POWER_ON, command(0xCD, code))
                pause(wait)
            }
            sendOk(WriteStep.PREPARE_DATA, command(0xCD, 0x07, 0x00))
            // The vendor sequence pauses here for a CD 08 data loop that the
            // 2.7" skips; keep the pause.
            pause(PREPARE_PAUSE_MS)
            sendOk(WriteStep.START_DATA, command(0xCD, 0x18))
            pause(START_PAUSE_MS)

            for (chunk in 0 until panel.chunkCount) {
                sendOk(WriteStep.SEND_DATA, dataPacket(panel, frame, chunk), "chunk ${chunk + 1}/${panel.chunkCount}")
                onProgress((chunk + 1) * DATA_PROGRESS / panel.chunkCount)
                pause(CHUNK_PAUSE_MS)
            }

            pause(PRE_REFRESH_PAUSE_MS)
            sendOk(WriteStep.REFRESH, command(0xCD, 0x09))
            pause(REFRESH_WAIT_MS)
            awaitRefresh(maxPolls(panel))
            sendOk(WriteStep.POWER_OFF, command(0xCD, 0x04))
            onProgress(100)
        }
    }

    private suspend fun awaitRefresh(maxPolls: Int) {
        repeat(maxPolls) {
            if (isDone(transport.exchange(WriteStep.WAIT_FOR_REFRESH, command(0xCD, 0x0A)))) return
            pause(POLL_PAUSE_MS)
        }
        throw StepFailed(WriteStep.WAIT_FOR_REFRESH, FailureKind.REFRESH_UNCONFIRMED, "Refresh not confirmed after $maxPolls polls")
    }

    /** Busy polls left in the write budget once everything else for [panel] is sent. */
    private fun maxPolls(panel: WavesharePanel) = busyPollLimit(
        pausesMs = SELECT_PAUSE_MS + POWER_ON_SEQUENCE.sumOf { it.second } + PREPARE_PAUSE_MS + START_PAUSE_MS +
            panel.chunkCount * CHUNK_PAUSE_MS + PRE_REFRESH_PAUSE_MS + REFRESH_WAIT_MS,
        // Init, select panel, power on, prepare, start, the chunks, refresh, and power off after.
        commands = 2 + POWER_ON_SEQUENCE.size + 2 + panel.chunkCount + 2,
        pollPauseMs = POLL_PAUSE_MS,
    )

    private suspend fun sendOk(step: WriteStep, command: ByteArray, detail: String? = null) =
        transport.exchangeOk(step, command, ::isOk, detail)

    private companion object {
        const val DATA_PROGRESS = IMAGE_SENT_PROGRESS
        const val SELECT_PAUSE_MS = 50L
        const val PREPARE_PAUSE_MS = 96L
        const val START_PAUSE_MS = 100L
        const val CHUNK_PAUSE_MS = 2L
        const val PRE_REFRESH_PAUSE_MS = 300L
        const val REFRESH_WAIT_MS = 2000L
        const val POLL_PAUSE_MS = 25L

        /** Power and configuration commands, each followed by a pause in ms. */
        val POWER_ON_SEQUENCE = listOf(0x01 to 20L, 0x02 to 20L, 0x03 to 20L, 0x05 to 20L, 0x06 to 10L)

        fun command(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

        fun dataPacket(panel: WavesharePanel, frame: ByteArray, chunk: Int): ByteArray {
            val start = chunk * panel.chunkSize
            return command(0xCD, 0x19, panel.chunkSize) + frame.copyOfRange(start, start + panel.chunkSize)
        }

        fun isOk(reply: ByteArray) = reply.size >= 2 && reply[0] == 0x00.toByte() && reply[1] == 0x00.toByte()

        fun isDone(reply: ByteArray) = reply.size >= 2 && reply[0] == 0xFF.toByte() && reply[1] == 0x00.toByte()
    }
}
