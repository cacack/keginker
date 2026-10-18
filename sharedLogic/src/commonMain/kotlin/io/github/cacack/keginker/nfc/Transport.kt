package io.github.cacack.keginker.nfc

/**
 * Sends one raw command to an NFC tag and returns its reply.
 *
 * Each platform implements this over its own NFC API; the shared core only
 * ever talks to tags through it, so every implementation keeps one contract:
 *
 * - **Replies.** [transceive] returns the tag's reply bytes. For ISO-DEP
 *   (ISO 7816 APDU) tags, the reply ends with the status word, SW1 SW2.
 * - **Failures throw.** A failed exchange, including the tag leaving the
 *   field or a command the platform rejects, throws; it never returns an
 *   empty or partial reply instead.
 * - **Framing.** Commands arrive without link-layer framing. The transport
 *   adds whatever its platform API leaves to the caller, such as the CRC_A
 *   that Core NFC needs on MIFARE Ultralight-family frames.
 * - **Timeouts.** Where the platform has a per-command timeout, `timeoutMs`
 *   is applied before the command is sent; elsewhere the platform's own
 *   limits apply.
 * - **One at a time.** Commands never overlap on the tag. Callers await each
 *   reply before sending the next command; a transport may also serialize
 *   calls itself, as Android's does across instances.
 * - **Closing.** Whoever receives a transport owns it and must [close] it
 *   when done, which releases the tag connection. [close] never throws and
 *   may be called more than once.
 *
 * Each platform's transport tests check the clauses its transport enforces
 * against a fake of its NFC API. Threading and timeout clamping differ by
 * platform; each transport's own KDoc states them, and its tests check them.
 */
interface Transport : AutoCloseable {
    /** Throws if the exchange fails, including when the tag leaves the field. */
    suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray

    override fun close() {}
}
