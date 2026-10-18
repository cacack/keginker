package io.github.cacack.keginker.nfc

/**
 * Sends one raw command to an NFC tag and returns its reply.
 *
 * Each platform implements this over its own NFC API; the shared core only
 * ever talks to tags through it.
 *
 * Whoever receives a transport owns it and must [close] it when done, which
 * releases the tag connection.
 */
interface Transport : AutoCloseable {
    /** Throws if the exchange fails, including when the tag leaves the field. */
    suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray

    override fun close() {}
}
