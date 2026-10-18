package io.github.cacack.tagpaper.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Finds tags with NFC reader mode while an activity is in the foreground.
 *
 * Reader mode hands every NFC-A tag to tagpaper and stops Android from
 * dispatching it elsewhere, so a Waveshare tag's launcher record doesn't open
 * the vendor app.
 *
 * [debug] enables exchange logging and [simulateWaveshareTag].
 */
class TagReader(private val adapter: NfcAdapter?, private val debug: Boolean) {
    private val found = MutableSharedFlow<FoundTag>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Tags as they're found, classified. Not replayed: a tag found while
     * nothing collects is dropped, since its transport may already be stale.
     * Collectors own each Waveshare tag's transport.
     */
    val tags: SharedFlow<FoundTag> = found

    /** Whether this device has NFC hardware at all. */
    val isSupported: Boolean get() = adapter != null

    /** Whether NFC is switched on in system settings; re-read in onResume. */
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /** Call from [Activity.onResume]. */
    fun start(activity: Activity) {
        adapter?.enableReaderMode(activity, ::onTagDiscovered, READER_FLAGS, null)
    }

    /** Call from [Activity.onPause]. */
    fun stop(activity: Activity) {
        adapter?.disableReaderMode(activity)
    }

    /** Pretends a Waveshare tag was found, for trying the flow without one. Debug only. */
    fun simulateWaveshareTag() {
        check(debug) { "Tag simulation is only available in debug builds" }
        found.tryEmit(FoundTag.Waveshare(SimulatedWaveshareTransport()))
    }

    private fun onTagDiscovered(tag: Tag) {
        // ISO-DEP tags (Waveshare Rev2.2) also expose NfcA, so check ISO-DEP first.
        val isoDep = IsoDep.get(tag)
        val classified = if (isoDep != null) {
            classifyIsoDepTag(tag.id, isoDep.historicalBytes ?: ByteArray(0)) {
                NfcTransport.forIsoDep(isoDep, logExchanges = debug)
            }
        } else {
            val nfcA = NfcA.get(tag) ?: return
            classifyNfcATag(tag.id) { NfcTransport.forNfcA(nfcA, logExchanges = debug) }
        }
        found.tryEmit(classified)
    }

    private companion object {
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    }
}
