package io.github.cacack.keginker.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Finds tags with NFC reader mode while an activity is in the foreground.
 *
 * Reader mode hands every NFC-A, NFC-B, NFC-F and NFC-V tag to KegInker and
 * stops Android from dispatching it elsewhere, so a Waveshare tag's launcher
 * record doesn't open the vendor app. One reader config serves both screens:
 * the display probe reads every kind, and writing reports anything but a
 * Waveshare tag as unsupported.
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
        val transport = SimulatedWaveshareTransport(failAfterCommands = SimulatedWaveshareTransport.FAIL_WHILE_SENDING_IMAGE)
        found.tryEmit(FoundTag.Waveshare(transport, SIMULATED_TAG_INFO))
    }

    private fun onTagDiscovered(tag: Tag) {
        val nfcA = NfcA.get(tag)
        val isoDep = IsoDep.get(tag)
        val nfcV = NfcV.get(tag)
        val info = TagInfo(
            technologies = tag.techList.map { it.substringAfterLast('.') },
            uid = tag.id,
            atqa = nfcA?.atqa,
            sak = nfcA?.sak?.toInt(),
            historicalBytes = isoDep?.historicalBytes,
            details = details(tag),
        )
        // ISO-DEP tags (Waveshare Rev2.2) also expose NfcA, so check ISO-DEP first.
        val classified = when {
            isoDep != null -> classifyIsoDepTag(info) { NfcTransport.forIsoDep(isoDep, logExchanges = debug) }
            nfcA != null -> classifyNfcATag(info) { NfcTransport.forNfcA(nfcA, logExchanges = debug) }
            nfcV != null -> classifyNfcVTag(info) { NfcTransport.forNfcV(nfcV, logExchanges = debug) }
            else -> FoundTag.Unsupported(info)
        }
        found.tryEmit(classified)
    }

    /**
     * What each technology the tag offers reports about it. A report is better
     * without details than not at all, so a technology that fails to answer
     * (the tag may already be gone) ends the list there.
     */
    private fun details(tag: Tag): List<TagDetail> = buildList {
        runCatching {
            NfcA.get(tag)?.let { addAll(TagDetails.nfcA(it.maxTransceiveLength, it.timeout)) }
            IsoDep.get(tag)?.let {
                addAll(TagDetails.isoDep(it.hiLayerResponse, it.isExtendedLengthApduSupported, it.maxTransceiveLength, it.timeout))
            }
            NfcB.get(tag)?.let { addAll(TagDetails.nfcB(it.applicationData, it.protocolInfo, it.maxTransceiveLength)) }
            NfcF.get(tag)?.let { addAll(TagDetails.nfcF(it.manufacturer, it.systemCode, it.maxTransceiveLength)) }
            NfcV.get(tag)?.let { addAll(TagDetails.nfcV(it.dsfId, it.responseFlags, it.maxTransceiveLength)) }
            MifareUltralight.get(tag)?.let { addAll(TagDetails.mifareUltralight(it.type)) }
            MifareClassic.get(tag)?.let { addAll(TagDetails.mifareClassic(it.type, it.size, it.sectorCount)) }        }
    }

    private companion object {
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        val SIMULATED_TAG_INFO = TagInfo(technologies = listOf("Simulated"), uid = ByteArray(0))
    }
}
