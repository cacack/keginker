package io.github.cacack.tagpaper.nfc

import android.nfc.tech.NfcA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [Transport] over Android [NfcA]: raw ISO 14443-3A frames.
 *
 * Connects on first use and applies each command's timeout with
 * [NfcA.setTimeout], so slow steps such as a panel refresh aren't cut short.
 * I/O never runs on the main thread, and calls from all instances are
 * serialized, since only one tag is in the field at a time.
 */
class NfcATransport(private val nfcA: NfcA) : Transport {
    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray =
        withContext(nfcIo) {
            if (!nfcA.isConnected) nfcA.connect()
            nfcA.timeout = timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            nfcA.transceive(command)
        }

    override fun close() {
        runCatching { nfcA.close() }
    }

    private companion object {
        /** NfcA calls must not overlap. */
        val nfcIo = Dispatchers.IO.limitedParallelism(1)
    }
}
