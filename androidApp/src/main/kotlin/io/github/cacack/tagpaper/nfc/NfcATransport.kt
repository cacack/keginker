package io.github.cacack.tagpaper.nfc

import android.nfc.tech.NfcA
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [Transport] over Android [NfcA]: raw ISO 14443-3A frames.
 *
 * Connects on first use and applies each command's timeout with
 * [NfcA.setTimeout], so slow steps such as a panel refresh aren't cut short.
 * I/O never runs on the main thread, and calls from all instances are
 * serialized, since only one tag is in the field at a time.
 * Every exchange is logged under the `tagpaper-nfc` tag.
 */
class NfcATransport(private val nfcA: NfcA) : Transport {
    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray =
        withContext(nfcIo) {
            if (!nfcA.isConnected) nfcA.connect()
            nfcA.timeout = timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            Log.d(LOG_TAG, "> ${command.toHex()}")
            try {
                nfcA.transceive(command).also { Log.d(LOG_TAG, "< ${it.toHex()}") }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "! ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }

    override fun close() {
        runCatching { nfcA.close() }
    }

    private companion object {
        const val LOG_TAG = "tagpaper-nfc"

        fun ByteArray.toHex() = joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        /** NfcA calls must not overlap. */
        val nfcIo = Dispatchers.IO.limitedParallelism(1)
    }
}
