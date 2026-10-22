package io.github.cacack.keginker.probe

import io.github.cacack.keginker.diagnostics.describe
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.TagInfo
import io.github.cacack.keginker.nfc.TagKind
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.nfc.kind
import io.github.cacack.keginker.nfc.protocolName
import io.github.cacack.keginker.waveshare.TRANSCEIVE_TIMEOUT_MS
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** Most NDEF message bytes the probe reads; a launcher record is far smaller. */
internal const val MAX_NDEF_BYTES = 1_024

/** Most bytes one Type 4 READ BINARY asks for. */
private const val TYPE4_CHUNK = 250

/** Most Type 2 READs (16 bytes each) the probe sends. */
internal const val MAX_TYPE2_READS = 40

/** The first Type 2 page the probe reads: the capability container. Pages 0–2 hold the UID, which reports shorten. */
private const val TYPE2_FIRST_PAGE = 3

/** A step of the display probe, for its report. */
enum class ProbeStep(val activity: String) {
    SELECT_AID("selecting an application"),
    SELECT_CC("selecting the capability container"),
    READ_CC("reading the capability container"),
    SELECT_NDEF("selecting the NDEF file"),
    READ_NDEF_LENGTH("reading the NDEF length"),
    READ_NDEF("reading the NDEF message"),
    READ_PAGES("reading memory pages"),
}

/** One probe command and what came back; exactly one of [reply] and [error] is set. */
class ProbeExchange internal constructor(
    /** Milliseconds from the start of the probe to when the command was sent. */
    val atMs: Long,
    val step: ProbeStep,
    val command: ByteArray,
    val reply: ByteArray?,
    /** The exception's class name and message, if the exchange failed. */
    val error: String?,
)

/**
 * What a read-only probe learned about a tag, for [io.github.cacack.keginker.diagnostics.formatDisplayReport].
 * [ndef] is null when no NDEF message was found; [note] says why the probe
 * stopped short, if it did.
 */
class ProbeResult(
    /** Null if the platform didn't capture it. */
    val tag: TagInfo?,
    /** From [io.github.cacack.keginker.nfc.protocolName]. */
    val protocol: String,
    val kind: TagKind,
    /** The [PROBE_AIDS] the tag accepted; always empty for a tag that isn't ISO-DEP. */
    val acceptedAids: List<ByteArray>,
    /** The Type 4 capability container file, or the Type 2 capability container page. */
    val capabilityContainer: ByteArray?,
    val ndef: NdefMessage?,
    val note: String?,
    val exchanges: List<ProbeExchange>,
)

/**
 * Reads what [tag] says about itself, sending only the read commands in
 * ProbeCommands.kt. Takes ownership of the tag's transport and closes it.
 * Never throws, except to pass on cancellation.
 */
suspend fun probeDisplay(tag: FoundTag): ProbeResult = probeDisplay(tag, TimeSource.Monotonic)

internal suspend fun probeDisplay(tag: FoundTag, timeSource: TimeSource): ProbeResult {
    val transport = when (tag) {
        is FoundTag.Writable -> tag.transport
        is FoundTag.Unsupported -> tag.openTransport?.invoke()
    }
    val probe = Probe(transport, timeSource)
    try {
        when {
            transport == null -> probe.note = "The probe can't send commands to this kind of tag."
            tag.kind == TagKind.ISO_DEP -> probe.type4()
            tag.kind == TagKind.NFC_A -> probe.type2()
            else -> probe.note = "The probe can't send commands to this kind of tag."
        }
    } catch (_: Stopped) {
        // The note says why.
    } finally {
        transport?.close()
    }
    return ProbeResult(
        tag = tag.info,
        protocol = tag.protocolName,
        kind = tag.kind,
        acceptedAids = probe.acceptedAids,
        capabilityContainer = probe.capabilityContainer,
        ndef = probe.ndef,
        note = probe.note,
        exchanges = probe.exchanges,
    )
}

/** The probe ended early; [Probe.note] says why. */
private class Stopped : Exception()

private class Probe(private val transport: Transport?, timeSource: TimeSource) {
    private val start = timeSource.markNow()
    val exchanges = mutableListOf<ProbeExchange>()
    val acceptedAids = mutableListOf<ByteArray>()
    var capabilityContainer: ByteArray? = null
    var ndef: NdefMessage? = null
    var note: String? = null

    /** NFC Forum Type 4: which AIDs the tag accepts, then its NDEF message through the capability container. */
    suspend fun type4() {
        for (aid in PROBE_AIDS) {
            if (!isOk(send(ProbeStep.SELECT_AID, selectAid(aid)))) continue
            acceptedAids += aid
            if (aid.contentEquals(NDEF_APPLICATION_AID)) type4Ndef()
        }
        if (acceptedAids.isEmpty()) note = "The tag accepted none of the listed applications."
    }

    private suspend fun type4Ndef() {
        okData(ProbeStep.SELECT_CC, selectFile(CC_FILE_ID), "The tag has no capability container.")
        val cc = okData(ProbeStep.READ_CC, readBinary(0, 15), "The capability container couldn't be read.")
        capabilityContainer = cc
        if (cc.size < 15 || u8(cc, 7) != 0x04 || u8(cc, 8) < 6) stop("The capability container lists no NDEF file.")
        if (u8(cc, 13) != 0x00) stop("The NDEF file isn't readable.")
        val maxRead = (u8(cc, 3) shl 8 or u8(cc, 4)).takeIf { it >= 1 } ?: 15
        val chunk = minOf(maxRead, TYPE4_CHUNK)
        okData(ProbeStep.SELECT_NDEF, selectFile(cc.copyOfRange(9, 11)), "The NDEF file couldn't be selected.")
        val lengthBytes = okData(ProbeStep.READ_NDEF_LENGTH, readBinary(0, 2), "The NDEF length couldn't be read.")
        if (lengthBytes.size < 2) stop("The NDEF length couldn't be read.")
        val length = u8(lengthBytes, 0) shl 8 or u8(lengthBytes, 1)
        val wanted = minOf(length, MAX_NDEF_BYTES)
        var message = ByteArray(0)
        while (message.size < wanted) {
            val data = okData(
                ProbeStep.READ_NDEF,
                readBinary(2 + message.size, minOf(chunk, wanted - message.size)),
                "The NDEF message couldn't be read.",
            )
            if (data.isEmpty()) stop("The NDEF message couldn't be read.")
            message += data
        }
        ndef = parseNdefMessage(message.copyOfRange(0, wanted))
        if (length > wanted) note = "The NDEF message is $length bytes; only the first $wanted were read."
    }

    /**
     * NFC Forum Type 2: the capability container page, then the data area it
     * declares until the NDEF TLV is complete. Reads stay inside the data
     * area: on many tags a read past the last page wraps around to the UID pages.
     */
    suspend fun type2() {
        var memory = ByteArray(0)
        var lastPage = 0xFF
        for (read in 0 until MAX_TYPE2_READS) {
            val page = TYPE2_FIRST_PAGE + read * 4
            if (page > lastPage) stop("No complete NDEF message in the tag's data area.")
            // A read returns 4 pages; near the end, read the last 4 and keep only the new ones.
            val from = minOf(page, lastPage - 3)
            val reply = send(ProbeStep.READ_PAGES, type2Read(from))
            if (reply.size != 16) stop(if (read == 0) "The tag doesn't answer Type 2 reads." else "The tag stopped answering Type 2 reads.")
            memory += reply.copyOfRange((page - from) * 4, 16)
            if (capabilityContainer == null) {
                capabilityContainer = memory.copyOfRange(0, 4)
                // Byte 2 is the data area's size in 8-byte units, from page 4.
                val dataPages = u8(memory, 2) * 2
                if (dataPages < 4) stop("The capability container declares no data area.")
                lastPage = minOf(TYPE2_FIRST_PAGE + dataPages, 0xFF)
            }
            when (val found = findNdefTlv(memory.copyOfRange(4, memory.size))) {
                is TlvSearch.Found -> {
                    ndef = parseNdefMessage(found.message)
                    return
                }
                TlvSearch.Absent -> stop("The tag's memory holds no NDEF message.")
                TlvSearch.Incomplete -> Unit
            }
        }
        stop("No complete NDEF message in the first ${memory.size} bytes read.")
    }

    /** Sends [command] and returns its data without the status word, or stops with [failure] unless it ends in 90 00. */
    private suspend fun okData(step: ProbeStep, command: ByteArray, failure: String): ByteArray {
        val reply = send(step, command)
        if (!isOk(reply)) stop(failure)
        return reply.copyOfRange(0, reply.size - 2)
    }

    private suspend fun send(step: ProbeStep, command: ByteArray): ByteArray {
        val atMs = start.elapsedNow().inWholeMilliseconds
        val reply = try {
            checkNotNull(transport).transceive(command, TRANSCEIVE_TIMEOUT_MS)
        } catch (e: Exception) {
            exchanges += ProbeExchange(atMs, step, command.copyOf(), reply = null, error = describe(e))
            if (e is CancellationException) throw e
            stop("Lost contact with the tag while ${step.activity}.")
        }
        exchanges += ProbeExchange(atMs, step, command.copyOf(), reply = reply.copyOf(), error = null)
        return reply
    }

    private fun stop(why: String): Nothing {
        note = why
        throw Stopped()
    }
}

private fun isOk(reply: ByteArray) =
    reply.size >= 2 && reply[reply.size - 2] == 0x90.toByte() && reply[reply.size - 1] == 0x00.toByte()

private fun u8(bytes: ByteArray, index: Int) = bytes[index].toInt() and 0xFF

private sealed interface TlvSearch {
    class Found(val message: ByteArray) : TlvSearch

    /** A terminator TLV came first. */
    data object Absent : TlvSearch

    /** More memory is needed to tell. */
    data object Incomplete : TlvSearch
}

/** Finds the NDEF message TLV (NFC Forum Type 2) in the data area read so far. */
private fun findNdefTlv(area: ByteArray): TlvSearch {
    var at = 0
    while (at < area.size) {
        val type = u8(area, at)
        if (type == 0x00) {
            at++
            continue
        }
        if (type == 0xFE) return TlvSearch.Absent
        if (at + 1 >= area.size) return TlvSearch.Incomplete
        var length = u8(area, at + 1)
        var header = 2
        if (length == 0xFF) {
            if (at + 3 >= area.size) return TlvSearch.Incomplete
            length = u8(area, at + 2) shl 8 or u8(area, at + 3)
            header = 4
        }
        if (type == 0x03) {
            val end = at + header + length
            return if (end <= area.size) TlvSearch.Found(area.copyOfRange(at + header, end)) else TlvSearch.Incomplete
        }
        at += header + length
    }
    return TlvSearch.Incomplete
}
