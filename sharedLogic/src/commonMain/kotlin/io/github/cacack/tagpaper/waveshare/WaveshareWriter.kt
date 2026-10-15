/*
 * Derived from Magic ePaper's lib/waveshare/services/waveshare_nfc_protocol.dart
 * (https://github.com/fossasia/magic-epaper-app, commit 7c04b55),
 * licensed under the Apache License, Version 2.0.
 *
 * Modified: ported from Dart to Kotlin; limited to the 2.7" sequence; failures
 * are reported as a typed result naming the failing step; losing the tag while
 * waiting for refresh is a failure rather than a success.
 */
package io.github.cacack.tagpaper.waveshare

import io.github.cacack.tagpaper.nfc.Transport
import kotlin.coroutines.cancellation.CancellationException
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
}

sealed interface WriteResult {
    data object Success : WriteResult

    data class Failure(
        val step: WriteStep,
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
        return try {
            sendOk(WriteStep.INIT, command(0xCD, 0x0D))
            sendOk(WriteStep.SELECT_PANEL, command(0xCD, 0x00, panel.typeCode))
            pause(50)
            for ((code, wait) in POWER_ON_SEQUENCE) {
                sendOk(WriteStep.POWER_ON, command(0xCD, code))
                pause(wait)
            }
            sendOk(WriteStep.PREPARE_DATA, command(0xCD, 0x07, 0x00))
            // The vendor sequence pauses here for a CD 08 data loop that the
            // 2.7" skips; keep the pause.
            pause(96)
            sendOk(WriteStep.START_DATA, command(0xCD, 0x18))
            pause(100)

            for (chunk in 0 until panel.chunkCount) {
                sendOk(WriteStep.SEND_DATA, dataPacket(panel, frame, chunk), "chunk ${chunk + 1}/${panel.chunkCount}")
                onProgress((chunk + 1) * DATA_PROGRESS / panel.chunkCount)
                pause(2)
            }

            pause(300)
            sendOk(WriteStep.REFRESH, command(0xCD, 0x09))
            pause(2000)
            awaitRefresh()
            sendOk(WriteStep.POWER_OFF, command(0xCD, 0x04))
            onProgress(100)
            WriteResult.Success
        } catch (failure: StepFailed) {
            WriteResult.Failure(failure.step, failure.reason, failure.cause)
        }
    }

    private suspend fun awaitRefresh() {
        repeat(MAX_BUSY_POLLS) {
            if (isDone(send(WriteStep.WAIT_FOR_REFRESH, command(0xCD, 0x0A)))) return
            pause(25)
        }
        throw StepFailed(WriteStep.WAIT_FOR_REFRESH, "Refresh not confirmed after $MAX_BUSY_POLLS polls")
    }

    private suspend fun sendOk(step: WriteStep, command: ByteArray, detail: String? = null) {
        val reply = send(step, command, detail)
        if (!isOk(reply)) {
            throw StepFailed(step, listOfNotNull("Unexpected reply ${reply.toHex()}", detail).joinToString(" at "))
        }
    }

    private suspend fun send(step: WriteStep, command: ByteArray, detail: String? = null): ByteArray =
        try {
            transport.transceive(command, TRANSCEIVE_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw StepFailed(step, listOfNotNull("Tag communication failed", detail).joinToString(" at "), e)
        }

    private class StepFailed(
        val step: WriteStep,
        val reason: String,
        cause: Throwable? = null,
    ) : Exception(reason, cause)

    private companion object {
        const val TRANSCEIVE_TIMEOUT_MS = 3_000L
        const val MAX_BUSY_POLLS = 240
        const val DATA_PROGRESS = 90

        /** Power and configuration commands, each followed by a pause in ms. */
        val POWER_ON_SEQUENCE = listOf(0x01 to 20L, 0x02 to 20L, 0x03 to 20L, 0x05 to 20L, 0x06 to 10L)

        fun command(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

        fun dataPacket(panel: WavesharePanel, frame: ByteArray, chunk: Int): ByteArray {
            val start = chunk * panel.chunkSize
            return command(0xCD, 0x19, panel.chunkSize) + frame.copyOfRange(start, start + panel.chunkSize)
        }

        fun isOk(reply: ByteArray) = reply.size >= 2 && reply[0] == 0x00.toByte() && reply[1] == 0x00.toByte()

        fun isDone(reply: ByteArray) = reply.size >= 2 && reply[0] == 0xFF.toByte() && reply[1] == 0x00.toByte()

        fun ByteArray.toHex() = joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
    }
}
