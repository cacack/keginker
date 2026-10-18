package io.github.cacack.tagpaper.nfc

import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.nfc.tech.TagTechnology
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [Transport] over one Android tag technology: [forNfcA] for raw ISO 14443-3A
 * frames, [forIsoDep] for ISO-DEP APDUs (replies include the status word).
 *
 * Connects on first use and applies each command's timeout, so slow steps
 * such as a panel refresh aren't cut short. I/O never runs on the main thread,
 * and calls from all instances are serialized, since only one tag is in the
 * field at a time. With [logExchanges] (debug builds only, as frames carry the
 * user's image), every exchange is logged under the `tagpaper-nfc` tag.
 */
class NfcTransport private constructor(
    private val technology: TagTechnology,
    private val setTimeout: (Int) -> Unit,
    private val exchange: (ByteArray) -> ByteArray,
    private val logExchanges: Boolean,
) : Transport {
    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray =
        withContext(nfcIo) {
            if (!technology.isConnected) technology.connect()
            setTimeout(timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
            if (logExchanges) Log.d(LOG_TAG, "> ${command.toHex()}")
            try {
                exchange(command).also { if (logExchanges) Log.d(LOG_TAG, "< ${it.toHex()}") }
            } catch (e: Exception) {
                if (logExchanges) Log.w(LOG_TAG, "! ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }

    override fun close() {
        runCatching { technology.close() }
    }

    companion object {
        fun forNfcA(nfcA: NfcA, logExchanges: Boolean) =
            NfcTransport(nfcA, { nfcA.timeout = it }, nfcA::transceive, logExchanges)

        fun forIsoDep(isoDep: IsoDep, logExchanges: Boolean) =
            NfcTransport(isoDep, { isoDep.timeout = it }, isoDep::transceive, logExchanges)

        private const val LOG_TAG = "tagpaper-nfc"

        private fun ByteArray.toHex() = joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        /** Tag I/O calls must not overlap. */
        private val nfcIo = Dispatchers.IO.limitedParallelism(1)
    }
}
