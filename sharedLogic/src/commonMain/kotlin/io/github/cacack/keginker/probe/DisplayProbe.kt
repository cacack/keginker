package io.github.cacack.keginker.probe

import io.github.cacack.keginker.diagnostics.describe
import io.github.cacack.keginker.ezsign.EzSignDeviceInfo
import io.github.cacack.keginker.ezsign.EzSignDeviceInfoParse
import io.github.cacack.keginker.ezsign.parseEzSignDeviceInfo
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.TagInfo
import io.github.cacack.keginker.nfc.TagKind
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.nfc.kind
import io.github.cacack.keginker.nfc.protocolName
import io.github.cacack.keginker.waveshare.TRANSCEIVE_TIMEOUT_MS
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.withTimeoutOrNull

/** Most NDEF message bytes the probe reads; a launcher record is far smaller. */
internal const val MAX_NDEF_BYTES = 1_024

/** The protocol a report names for a tag whose [DEVICE_INFO] reply decoded. */
internal const val EZ_SIGN_PROTOCOL = "EZ Sign (ISO-DEP), no writer yet"

/** Most bytes one Type 4 READ BINARY asks for. */
private const val TYPE4_CHUNK = 250

/** Most Type 2 READs (16 bytes each) the probe sends. */
internal const val MAX_TYPE2_READS = 40

/** Most NFC-V blocks the probe reads; e-paper tags keep their data in the first few. */
internal const val MAX_NFCV_BLOCKS = 64

/**
 * Most commands one probe sends across both passes. The documented reads of
 * a working tag take far fewer; this bounds a tag that answers in tiny pieces.
 */
internal const val MAX_PROBE_EXCHANGES = 120

/**
 * The real time a probe may take before it stops. The tag sets how many reads
 * it takes (up to [MAX_PROBE_EXCHANGES]), and on Android each may take up to
 * [TRANSCEIVE_TIMEOUT_MS]. On a working tag a probe is at most a few dozen
 * short reads, well under a second, so 10 s leaves ample room.
 */
internal const val PROBE_DEADLINE_MS = 10_000L

/** The first Type 2 page the probe reads: the capability container. Pages 0–2 hold the UID, which reports shorten. */
private const val TYPE2_FIRST_PAGE = 3

/** NXP GET_VERSION's reply length. */
private const val VERSION_BYTES = 8

/** NXP READ_SIG's reply length. */
private const val SIGNATURE_BYTES = 32

/** A step of the display probe, for its report. */
enum class ProbeStep(val activity: String) {
    SELECT_AID("selecting an application"),
    SELECT_CC("selecting the capability container"),
    READ_CC("reading the capability container"),
    SELECT_NDEF("selecting the NDEF file"),
    READ_NDEF_LENGTH("reading the NDEF length"),
    READ_NDEF("reading the NDEF message"),
    READ_PAGES("reading memory pages"),
    GET_VERSION("reading the product version"),
    READ_SIGNATURE("reading the originality signature"),
    GET_SYSTEM_INFO("reading the system information"),
    READ_BLOCKS("reading memory blocks"),
    VENDOR_QUERY("sending a vendor query"),
}

/** Which of the probe's passes sent a command. */
enum class ProbePass {
    /** The reads in ProbeCommands.kt that a standard or datasheet defines. */
    DOCUMENTED,

    /** The opt-in [VENDOR_QUERIES], sent after every documented read. */
    VENDOR,
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
    val pass: ProbePass,
)

/**
 * What an NFC-V tag's ISO/IEC 15693-3 Get System Information reply declares;
 * null where the tag left a field out. The reply's UID is deliberately not kept.
 */
class NfcVSystemInfo(
    val dsfid: Int?,
    val afi: Int?,
    val blockCount: Int?,
    /** In bytes. */
    val blockSize: Int?,
    val icReference: Int?,
)

/**
 * What a read-only probe learned about a tag, for [io.github.cacack.keginker.diagnostics.formatDisplayReport].
 * [ndef] is null when no NDEF message was found; [note] says why the probe
 * stopped short, if it did.
 */
class ProbeResult(
    /** Null if the platform didn't capture it. */
    val tag: TagInfo?,
    /** From [io.github.cacack.keginker.nfc.protocolName], or [EZ_SIGN_PROTOCOL] when [deviceInfo] decoded. */
    val protocol: String,
    val kind: TagKind,
    /** The [PROBE_AIDS] the tag accepted; always empty for a tag that isn't ISO-DEP. */
    val acceptedAids: List<ByteArray>,
    /** The Type 4 capability container file, or the Type 2 or Type 5 capability container. */
    val capabilityContainer: ByteArray?,
    val ndef: NdefMessage?,
    val note: String?,
    val exchanges: List<ProbeExchange>,
    /** The NXP GET_VERSION reply, if an NFC-A tag gave one. */
    val version: ByteArray? = null,
    /** The NXP READ_SIG reply, if an NFC-A tag gave one. */
    val signature: ByteArray? = null,
    /** What an NFC-V tag declared about itself, if it answered. */
    val systemInfo: NfcVSystemInfo? = null,
    /** The tag's memory from [memoryAddress], as far as it was read: an NFC-V tag's from block 0, a Type 2 tag's from page 3. */
    val memory: ByteArray? = null,
    /** The byte address of [memory]'s first byte. */
    val memoryAddress: Int = 0,
    /** How many [VENDOR_QUERIES] were listed for the tag's kind; null when the vendor pass was off. */
    val vendorQueriesListed: Int? = null,
    /** How many of the listed queries the vendor pass began sending. */
    val vendorQueriesSent: Int = 0,
    /** Why the vendor pass didn't run though it was on and queries were listed; null if it ran. */
    val vendorPassSkipped: String? = null,
    /** The vendor pass's [DEVICE_INFO] reply, decoded; null if the query wasn't sent or didn't decode. */
    val deviceInfo: EzSignDeviceInfo? = null,
    /** Why the [DEVICE_INFO] reply didn't decode; null if none came back, or it decoded. */
    val deviceInfoNotDecoded: String? = null,
)

/**
 * Reads what [tag] says about itself, sending only the read commands in
 * ProbeCommands.kt, then, if [vendorQueries], the [VENDOR_QUERIES] for its
 * kind. Takes ownership of the tag's transport and closes it. Stops after
 * [PROBE_DEADLINE_MS] or [MAX_PROBE_EXCHANGES] commands and says so in its
 * note. Never throws, except to pass on cancellation.
 */
suspend fun probeDisplay(tag: FoundTag, vendorQueries: Boolean = false): ProbeResult =
    probeDisplay(tag, vendorQueries, VENDOR_QUERIES)

internal suspend fun probeDisplay(
    tag: FoundTag,
    vendorQueries: Boolean,
    queries: List<VendorQuery>,
    timeSource: TimeSource = TimeSource.Monotonic,
): ProbeResult {
    val transport = when (tag) {
        is FoundTag.Writable -> tag.transport
        is FoundTag.Unsupported -> tag.openTransport?.invoke()
    }
    val probe = Probe(transport, timeSource)
    val reads: (suspend () -> Unit)? = when {
        transport == null -> null
        tag.kind == TagKind.ISO_DEP -> probe::type4
        tag.kind == TagKind.NFC_A -> probe::type2
        tag.kind == TagKind.NFC_V -> probe::nfcV
        else -> null
    }
    val listed = queries.filter { it.kind == tag.kind && it.commands.isNotEmpty() }
    try {
        withTimeoutOrNull(PROBE_DEADLINE_MS) {
            if (reads == null) {
                probe.addNote("The probe can't send commands to this kind of tag.")
            } else {
                probe.runPass(ProbePass.DOCUMENTED, reads)
                // After any documented stop but these: a tag that refused a read may still answer its vendor's queries.
                when {
                    !vendorQueries || listed.isEmpty() -> Unit
                    probe.capped -> probe.vendorPassSkipped = "the probe reached its command limit"
                    probe.lostContact -> {
                        probe.vendorPassSkipped = "contact with the tag was lost"
                        probe.addNote("The vendor queries weren't sent.")
                    }
                    else -> probe.runPass(ProbePass.VENDOR) { probe.vendor(listed) }
                }
            }
        } ?: run { probe.addNote("The tag answered too slowly; the probe stopped after ${PROBE_DEADLINE_MS / 1_000} s.") }
    } finally {
        transport?.close()
    }
    val parsed = probe.deviceInfoReply?.let(::parseEzSignDeviceInfo)
    val deviceInfo = (parsed as? EzSignDeviceInfoParse.Decoded)?.info
    return ProbeResult(
        tag = tag.info,
        // Only a tag no supported protocol recognised is named by its reply.
        protocol = if (deviceInfo != null && tag is FoundTag.Unsupported) EZ_SIGN_PROTOCOL else tag.protocolName,
        kind = tag.kind,
        acceptedAids = probe.acceptedAids,
        capabilityContainer = probe.capabilityContainer,
        ndef = probe.ndef,
        note = probe.note,
        exchanges = probe.exchanges,
        version = probe.version,
        signature = probe.signature,
        systemInfo = probe.systemInfo,
        memory = probe.memory,
        memoryAddress = probe.memoryAddress,
        vendorQueriesListed = if (vendorQueries) listed.size else null,
        vendorQueriesSent = probe.vendorQueriesSent,
        vendorPassSkipped = probe.vendorPassSkipped,
        deviceInfo = deviceInfo,
        deviceInfoNotDecoded = (parsed as? EzSignDeviceInfoParse.NotDecoded)?.reason,
    )
}

/** The probe stopped short; [Probe.note] says why. [endsPass] when the rest of the pass can't run either. */
private class Stopped(val endsPass: Boolean) : Exception()

private class Probe(private val transport: Transport?, timeSource: TimeSource) {
    private val start = timeSource.markNow()
    private var pass = ProbePass.DOCUMENTED
    val exchanges = mutableListOf<ProbeExchange>()
    val acceptedAids = mutableListOf<ByteArray>()
    var capabilityContainer: ByteArray? = null
    var ndef: NdefMessage? = null
    var version: ByteArray? = null
    var signature: ByteArray? = null
    var systemInfo: NfcVSystemInfo? = null
    var memory: ByteArray? = null
    var memoryAddress = 0
    var vendorQueriesSent = 0
    var vendorPassSkipped: String? = null

    /** The vendor pass's reply to [DEVICE_INFO], if one came back. */
    var deviceInfoReply: ByteArray? = null
    var note: String? = null
        private set

    /** Whether the probe reached [MAX_PROBE_EXCHANGES]. */
    var capped = false
        private set

    /** Whether a read the probe needed failed, so the tag is likely gone. */
    var lostContact = false
        private set

    /** Runs [reads] as [pass]; a stop ends only them, and the note says why. */
    suspend fun runPass(pass: ProbePass, reads: suspend () -> Unit) {
        this.pass = pass
        try {
            reads()
        } catch (_: Stopped) {
            // The note says why.
        }
    }

    /** Adds [why] to the note, after anything already in it. */
    fun addNote(why: String) {
        note = note?.let { "$it $why" } ?: why
    }

    /** NFC Forum Type 4: which AIDs the tag accepts, then its NDEF message through the capability container. */
    suspend fun type4() {
        for (aid in PROBE_AIDS) {
            if (!isOk(send(ProbeStep.SELECT_AID, selectAid(aid)))) continue
            acceptedAids += aid
            if (aid.contentEquals(NDEF_APPLICATION_AID)) type4Ndef()
        }
        if (acceptedAids.isEmpty()) addNote("The tag accepted none of the listed applications.")
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
        if (length > wanted) addNote("The NDEF message is $length bytes; only the first $wanted were read.")
    }

    /**
     * NFC Forum Type 2: the NDEF reads, then NXP's GET_VERSION and READ_SIG.
     * The NXP reads come last because a tag that NAKs one may need waking
     * before it answers anything again; an error reply to either, or none, is only recorded.
     */
    suspend fun type2() {
        try {
            type2Ndef()
        } catch (e: Stopped) {
            if (e.endsPass) throw e
        }
        version = sendOptional(ProbeStep.GET_VERSION, GET_VERSION)?.takeIf { it.size == VERSION_BYTES }
        signature = sendOptional(ProbeStep.READ_SIGNATURE, READ_SIG)?.takeIf { it.size == SIGNATURE_BYTES }
    }

    /**
     * The Type 2 capability container page, then the data area it declares
     * until the NDEF TLV is complete. Reads stay inside the data area: on many
     * tags a read past the last page wraps around to the UID pages. What's
     * read is kept as [memory] as it arrives, so a stop still leaves it.
     */
    private suspend fun type2Ndef() {
        var data = ByteArray(0)
        memoryAddress = TYPE2_FIRST_PAGE * 4
        var lastPage = 0xFF
        for (read in 0 until MAX_TYPE2_READS) {
            val page = TYPE2_FIRST_PAGE + read * 4
            if (page > lastPage) stop("No complete NDEF message in the tag's data area.")
            // A read returns 4 pages; near the end, read the last 4 and keep only the new ones.
            val from = minOf(page, lastPage - 3)
            val reply = send(ProbeStep.READ_PAGES, type2Read(from))
            if (reply.size != 16) stop(if (read == 0) "The tag doesn't answer Type 2 reads." else "The tag stopped answering Type 2 reads.")
            data += reply.copyOfRange((page - from) * 4, 16)
            memory = data
            if (capabilityContainer == null) {
                capabilityContainer = data.copyOfRange(0, 4)
                // A blank or unformatted tag's CC holds no size to trust, and reading on could wrap.
                if (u8(data, 0) != 0xE1) stop("The tag has no NDEF capability container.")
                // Byte 2 is the data area's size in 8-byte units, from page 4.
                val dataPages = u8(data, 2) * 2
                if (dataPages < 4) stop("The capability container declares no data area.")
                // Inclusive: the data area is pages 4 to 3 + dataPages.
                lastPage = minOf(TYPE2_FIRST_PAGE + dataPages, 0xFF)
            }
            when (val found = findNdefTlv(data.copyOfRange(4, data.size))) {
                is TlvSearch.Found -> {
                    ndef = parseNdefMessage(found.message)
                    return
                }
                TlvSearch.Absent -> stop("The tag's memory holds no NDEF message.")
                TlvSearch.Incomplete -> Unit
            }
        }
        stop("No complete NDEF message in the first ${data.size} bytes read.")
    }

    /**
     * NFC-V (ISO 15693): the system information, then memory blocks from 0
     * until the declared count, [MAX_NFCV_BLOCKS] or the first error reply,
     * then an NDEF message if the memory starts with a Type 5 capability
     * container. A tag that doesn't answer the system information is still read.
     */
    suspend fun nfcV() {
        val info = sendOptional(ProbeStep.GET_SYSTEM_INFO, GET_SYSTEM_INFO)?.let(::parseSystemInfo)
        systemInfo = info
        val declared = info?.blockCount
        val wanted = minOf(declared ?: MAX_NFCV_BLOCKS, MAX_NFCV_BLOCKS)
        var read = 0
        while (read < wanted) {
            val reply = send(ProbeStep.READ_BLOCKS, readSingleBlock(read))
            if (reply.size < 2 || !isNfcVOk(reply)) break
            // Kept block by block, so a lost contact later still leaves what was read.
            memory = (memory ?: ByteArray(0)) + reply.copyOfRange(1, reply.size)
            read++
        }
        when {
            declared != null && read < wanted -> addNote("The tag refused to read block $read.")
            declared != null && declared > MAX_NFCV_BLOCKS -> addNote("The tag has $declared blocks; only the first $MAX_NFCV_BLOCKS were read.")
            read == 0 -> addNote("The tag doesn't answer block reads.")
        }
        type5Ndef(memory ?: return)
    }

    /** NFC Forum Type 5: a capability container (4 bytes, or 8 when its size byte is 0), then TLVs as in Type 2. */
    private fun type5Ndef(memory: ByteArray) {
        if (u8(memory, 0) != 0xE1 && u8(memory, 0) != 0xE2) return addNote("The tag has no NDEF capability container.")
        val ccSize = if (memory.size >= 4 && u8(memory, 2) == 0) 8 else 4
        if (memory.size < ccSize) return addNote("The capability container couldn't be read.")
        capabilityContainer = memory.copyOfRange(0, ccSize)
        when (val found = findNdefTlv(memory.copyOfRange(ccSize, memory.size))) {
            is TlvSearch.Found -> ndef = parseNdefMessage(found.message)
            TlvSearch.Absent -> addNote("The tag's memory holds no NDEF message.")
            TlvSearch.Incomplete -> addNote("No complete NDEF message in the ${memory.size} bytes read.")
        }
    }

    /**
     * Sends the commands of each of [queries], whatever the replies, except
     * that on ISO-DEP a command refused (not `90 00`) ends its query, since
     * the commands after it rely on it.
     */
    suspend fun vendor(queries: List<VendorQuery>) {
        for (query in queries) {
            val first = exchanges.size
            try {
                for ((index, command) in query.commands.withIndex()) {
                    val step = if (isSelectAid(command)) ProbeStep.SELECT_AID else ProbeStep.VENDOR_QUERY
                    val reply = send(step, command)
                    if (command.contentEquals(DEVICE_INFO)) deviceInfoReply = reply
                    if (query.kind == TagKind.ISO_DEP && index < query.commands.lastIndex && !isOk(reply)) break
                }
            } finally {
                if (exchanges.size > first) vendorQueriesSent++
            }
        }
    }

    /** Sends [command] and returns its data without the status word, or stops with [failure] unless it ends in 90 00. */
    private suspend fun okData(step: ProbeStep, command: ByteArray, failure: String): ByteArray {
        val reply = send(step, command)
        if (!isOk(reply)) stop(failure)
        return reply.copyOfRange(0, reply.size - 2)
    }

    /** Sends a read the probe needs; if the tag doesn't answer, contact is lost and the pass ends. */
    private suspend fun send(step: ProbeStep, command: ByteArray): ByteArray =
        exchange(step, command) ?: run {
            lostContact = true
            stop("Lost contact with the tag while ${step.activity}.", endsPass = true)
        }

    /**
     * Sends a read the tag may not support, returning null if it doesn't
     * answer: Android's NfcA and NfcV throw on a NAK or silence rather than return it.
     */
    private suspend fun sendOptional(step: ProbeStep, command: ByteArray): ByteArray? =
        exchange(step, command) ?: run {
            addNote("The tag didn't answer ${step.name}.")
            null
        }

    /** Sends [command] and records it; null, recorded as an error, if the transport throws. */
    private suspend fun exchange(step: ProbeStep, command: ByteArray): ByteArray? {
        if (exchanges.size >= MAX_PROBE_EXCHANGES) {
            capped = true
            stop("The probe stopped at its limit of $MAX_PROBE_EXCHANGES commands.", endsPass = true)
        }
        val atMs = start.elapsedNow().inWholeMilliseconds
        val reply = try {
            checkNotNull(transport).transceive(command, TRANSCEIVE_TIMEOUT_MS)
        } catch (e: Exception) {
            exchanges += ProbeExchange(atMs, step, command.copyOf(), reply = null, error = describe(e), pass = pass)
            if (e is CancellationException) throw e
            return null
        }
        exchanges += ProbeExchange(atMs, step, command.copyOf(), reply = reply.copyOf(), error = null, pass = pass)
        return reply
    }

    private fun stop(why: String, endsPass: Boolean = false): Nothing {
        addNote(why)
        throw Stopped(endsPass)
    }
}

private fun isOk(reply: ByteArray) =
    reply.size >= 2 && reply[reply.size - 2] == 0x90.toByte() && reply[reply.size - 1] == 0x00.toByte()

/** Whether an ISO/IEC 15693-3 reply's error flag is clear. */
private fun isNfcVOk(reply: ByteArray) = reply.isNotEmpty() && u8(reply, 0) and 0x01 == 0

private fun u8(bytes: ByteArray, index: Int) = bytes[index].toInt() and 0xFF

/**
 * Parses a Get System Information reply: flags, info flags, the 8-byte UID
 * (skipped), then each field the info flags declare. Null for an error reply
 * or one too short for what it declares.
 */
private fun parseSystemInfo(reply: ByteArray): NfcVSystemInfo? {
    if (reply.size < 10 || !isNfcVOk(reply)) return null
    val flags = u8(reply, 1)
    fun has(bit: Int) = flags and bit != 0
    val needed = 10 + (if (has(0x01)) 1 else 0) + (if (has(0x02)) 1 else 0) + (if (has(0x04)) 2 else 0) + (if (has(0x08)) 1 else 0)
    if (reply.size < needed) return null
    var at = 10
    val dsfid = if (has(0x01)) u8(reply, at++) else null
    val afi = if (has(0x02)) u8(reply, at++) else null
    // Blocks minus one, then block size in bytes minus one (low 5 bits).
    val memorySize = if (has(0x04)) (u8(reply, at) to u8(reply, at + 1)).also { at += 2 } else null
    val icReference = if (has(0x08)) u8(reply, at) else null
    return NfcVSystemInfo(
        dsfid = dsfid,
        afi = afi,
        blockCount = memorySize?.let { it.first + 1 },
        blockSize = memorySize?.let { (it.second and 0x1F) + 1 },
        icReference = icReference,
    )
}

private sealed interface TlvSearch {
    class Found(val message: ByteArray) : TlvSearch

    /** A terminator TLV came first. */
    data object Absent : TlvSearch

    /** More memory is needed to tell. */
    data object Incomplete : TlvSearch
}

/** Finds the NDEF message TLV (NFC Forum Type 2 and Type 5) in the data area read so far. */
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
