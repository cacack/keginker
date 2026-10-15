package io.github.cacack.tagpaper.nfc

/**
 * Sends one raw command to an NFC tag and returns its reply.
 *
 * Each platform implements this over its own NFC API; the shared core only
 * ever talks to tags through it.
 */
interface Transport {
    suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray
}
