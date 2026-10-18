package io.github.cacack.keginker.nfc

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
 * Reader mode hands every NFC-A tag to KegInker and stops Android from
 * dispatching it elsewhere, so a Waveshare tag's launcher record doesn't open
 * the vendor app.
 *
 * [debug] enables exchange logging, [simulateWaveshareTag] and [simulateFailingTag].
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
        found.tryEmit(FoundTag.Waveshare(SimulatedWaveshareTransport(), SIMULATED_TAG_INFO))
    }

    /** Pretends a Waveshare tag was found and then lost mid-write, for trying the failure flow. Debug only. */
    fun simulateFailingTag() {
        check(debug) { "Tag simulation is only available in debug builds" }
        val transport = SimulatedWaveshareTransport(failAfterCommands = SIMULATED_FAILURE_AFTER_COMMANDS)
        found.tryEmit(FoundTag.Waveshare(transport, SIMULATED_TAG_INFO))
    }

    private fun onTagDiscovered(tag: Tag) {
        val nfcA = NfcA.get(tag)
        val isoDep = IsoDep.get(tag)
        val info = TagInfo(
            technologies = tag.techList.map { it.substringAfterLast('.') },
            uid = tag.id,
            atqa = nfcA?.atqa,
            sak = nfcA?.sak?.toInt(),
            historicalBytes = isoDep?.historicalBytes,
        )
        // ISO-DEP tags (Waveshare Rev2.2) also expose NfcA, so check ISO-DEP first.
        val classified = if (isoDep != null) {
            classifyIsoDepTag(info) { NfcTransport.forIsoDep(isoDep, logExchanges = debug) }
        } else {
            nfcA ?: return
            classifyNfcATag(info) { NfcTransport.forNfcA(nfcA, logExchanges = debug) }
        }
        found.tryEmit(classified)
    }

    private companion object {
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        /** Past the setup commands, so the simulated tag is lost while sending image data. */
        const val SIMULATED_FAILURE_AFTER_COMMANDS = 20

        val SIMULATED_TAG_INFO = TagInfo(technologies = listOf("Simulated"), uid = ByteArray(0))
    }
}
