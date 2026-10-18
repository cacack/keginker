package io.github.cacack.keginker.nfc

import kotlinx.coroutines.delay

/**
 * Behaves like a Waveshare classic tag that accepts every command, for
 * exercising the write flow on emulators and simulators without NFC.
 * Every write it receives "succeeds", so offer it only in debug builds.
 *
 * With [failAfterCommands], the tag is "lost" once that many commands have
 * been answered: every later command throws [SimulatedTagLostException].
 */
class SimulatedWaveshareTransport(
    private val commandDelayMs: Long = 20,
    private val failAfterCommands: Int? = null,
) : Transport {
    /** For Swift, which can't use the defaults. */
    constructor(commandDelayMs: Long) : this(commandDelayMs, null)

    private var answered = 0

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        delay(commandDelayMs)
        if (failAfterCommands != null && answered >= failAfterCommands) throw SimulatedTagLostException()
        answered++
        val isBusyPoll = command.size >= 2 && command[0] == 0xCD.toByte() && command[1] == 0x0A.toByte()
        return if (isBusyPoll) byteArrayOf(0xFF.toByte(), 0x00) else byteArrayOf(0x00, 0x00)
    }
}

/** Thrown by a [SimulatedWaveshareTransport] once its tag is "lost". */
class SimulatedTagLostException : Exception("Tag was lost")
