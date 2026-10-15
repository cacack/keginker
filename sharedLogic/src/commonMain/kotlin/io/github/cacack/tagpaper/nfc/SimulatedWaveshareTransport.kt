package io.github.cacack.tagpaper.nfc

import kotlinx.coroutines.delay

/**
 * Behaves like a Waveshare classic tag that accepts every command, for
 * exercising the write flow on emulators and simulators without NFC.
 * Debug builds only.
 */
class SimulatedWaveshareTransport(private val commandDelayMs: Long = 20) : Transport {
    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        delay(commandDelayMs)
        val isBusyPoll = command.size >= 2 && command[0] == 0xCD.toByte() && command[1] == 0x0A.toByte()
        return if (isBusyPoll) byteArrayOf(0xFF.toByte(), 0x00) else byteArrayOf(0x00, 0x00)
    }
}
