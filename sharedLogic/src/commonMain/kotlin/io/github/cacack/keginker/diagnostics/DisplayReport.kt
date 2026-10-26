package io.github.cacack.keginker.diagnostics

import io.github.cacack.keginker.nfc.TagKind
import io.github.cacack.keginker.probe.DEVICE_INFO
import io.github.cacack.keginker.probe.NfcVSystemInfo
import io.github.cacack.keginker.probe.ProbeExchange
import io.github.cacack.keginker.probe.ProbeResult
import io.github.cacack.keginker.probe.ProbePass
import io.github.cacack.keginker.probe.ProbeStep
import io.github.cacack.keginker.probe.describeNdefRecord
import io.github.cacack.keginker.waveshare.REPLY_SHOWN_BYTES
import io.github.cacack.keginker.waveshare.toHex

/** Bytes per row of memory a report shows. */
private const val MEMORY_ROW_BYTES = 16

/** Shortest UID the report hides inside replies; ISO 14443 UIDs are 4, 7 or 10 bytes, ISO 15693 UIDs 8. */
private const val MIN_HIDDEN_UID_BYTES = 4

/** Where an ISO/IEC 15693-3 Get System Information reply holds the 8-byte UID, after the flags and info flags. */
private val SYSTEM_INFO_UID = 2..9

/** The [DEVICE_INFO] reply TLVs that identify one device: C0 its serial, C1 its ID. */
private val DEVICE_ID_TLVS = setOf(0xC0, 0xC1)

/** The documented steps that read the tag's memory, whose data the report shows only under Memory. */
private val DATA_READS = setOf(ProbeStep.READ_PAGES, ProbeStep.READ_BLOCKS)

/**
 * The plain-text report of a read-only display probe, laid out like
 * [formatDiagnosticReport] and shared the same way. Like it, it shows only
 * the start of the tag's UID, even where a reply holds the whole UID, and
 * only the start of the originality signature and of a device's serial and
 * ID in a vendor query's reply. Memory reads show their data only under
 * Memory, where a UID split across two replies is still found and hidden.
 */
fun formatDisplayReport(app: AppInfo, device: DeviceInfo, result: ProbeResult): String = buildString {
    reportHeader("KegInker display report", app, device, result.tag)
    val uid = result.tag?.uid?.takeIf { it.size >= MIN_HIDDEN_UID_BYTES }
    fun hex(bytes: ByteArray) = hexHiding(bytes, 0, bytes.size, uidHidden(bytes, uid))
    appendLine("Detected protocol: ${result.protocol}")
    appendLine()
    appendLine("Probe (read-only)")
    field(
        "Vendor queries",
        when {
            result.vendorQueriesListed == null -> "off"
            result.vendorQueriesListed == 0 -> "on, none listed for this kind of tag"
            result.vendorPassSkipped != null -> "on, skipped: ${result.vendorPassSkipped}"
            else -> "on, ${result.vendorQueriesListed} listed, ${result.vendorQueriesSent} sent"
        },
    )
    if (result.kind == TagKind.ISO_DEP && result.exchanges.isNotEmpty()) {
        field("Applications accepted", result.acceptedAids.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.toHex() } ?: "none")
    }
    field("Capability container", result.capabilityContainer?.let(::hex))
    field("Product version", result.version?.let { "${hex(it)} (${describeVersion(it)})" })
    field("Signature", result.signature?.let(::shortUid))
    field("System information", result.systemInfo?.let(::describeSystemInfo))
    val ndef = result.ndef
    when {
        ndef == null -> field("NDEF", "none found")
        ndef.records.isEmpty() && ndef.error == null -> field("NDEF", "empty message")
        else -> {
            field("NDEF", "${ndef.records.size} record${if (ndef.records.size == 1) "" else "s"}")
            ndef.records.forEachIndexed { index, record -> appendLine("    ${index + 1}. ${describeNdefRecord(record)}") }
            ndef.error?.let { appendLine("    Stopped: $it") }
        }
    }
    result.memory?.let { memory ->
        appendLine("  Memory (${memory.size} bytes)")
        val hidden = uidHidden(memory, uid)
        for (from in memory.indices step MEMORY_ROW_BYTES) {
            val offset = (result.memoryAddress + from).toString(16).uppercase().padStart(4, '0')
            appendLine("    $offset  ${hexHiding(memory, from, minOf(from + MEMORY_ROW_BYTES, memory.size), hidden)}")
        }
    }
    field("Note", result.note)
    appendLine()
    exchangeLines(
        result.exchanges.map {
            ReportLine(
                atMs = it.atMs,
                step = "${it.pass.name.lowercase()} ${it.step.name}",
                command = it.command.toHex(),
                outcome = it.error?.let { error -> "! $error" } ?: "< ${replyText(it, uid)}",
            )
        },
    )
}

/** NXP GET_VERSION's fields (NTAG21x and MIFARE Ultralight EV1 datasheets). */
private fun describeVersion(version: ByteArray): String {
    if (version.size < 8) return "too short to decode"
    fun byte(index: Int) = version.copyOfRange(index, index + 1).toHex()
    val storage = version[6].toInt() and 0xFF
    // The top 7 bits give n: 2^n bytes exactly, or between 2^n and 2^(n+1) when the low bit is set.
    val n = storage shr 1
    val size = when {
        n >= 31 -> "unknown size"
        storage and 1 == 0 -> "${1L shl n} bytes"
        else -> "${1L shl n} to ${1L shl (n + 1)} bytes"
    }
    return "vendor ${byte(1)}, type ${byte(2)}, subtype ${byte(3)}, " +
        "version ${version[4].toInt() and 0xFF}.${version[5].toInt() and 0xFF}, storage ${byte(6)}: $size"
}

private fun describeSystemInfo(info: NfcVSystemInfo): String = listOfNotNull(
    info.dsfid?.let { "DSFID ${byteArrayOf(it.toByte()).toHex()}" },
    info.afi?.let { "AFI ${byteArrayOf(it.toByte()).toHex()}" },
    info.blockCount?.let { "$it blocks of ${info.blockSize} bytes" },
    info.icReference?.let { "IC reference ${byteArrayOf(it.toByte()).toHex()}" },
).joinToString(", ").ifEmpty { "no fields" }

/**
 * [exchange]'s reply as hex, cut to [REPLY_SHOWN_BYTES], with any UID,
 * signature or device ID in it hidden. A memory read's data is only counted.
 */
private fun replyText(exchange: ProbeExchange, uid: ByteArray?): String {
    val reply = exchange.reply ?: return ""
    dataReadText(exchange.step, reply)?.let { return it }
    // A NAK is one byte; anything longer is the signature, or close enough to hide.
    if (exchange.step == ProbeStep.READ_SIGNATURE && reply.size > 1) return "[signature ${shortUid(reply)}]"
    val hidden = uidHidden(reply, uid).toMutableList()
    // Hidden even when the platform didn't report the UID.
    if (exchange.step == ProbeStep.GET_SYSTEM_INFO && reply.size > SYSTEM_INFO_UID.last) {
        // ISO 15693 sends the UID least significant byte first; it's written the other way round.
        val shown = uid ?: reply.copyOfRange(SYSTEM_INFO_UID.first, SYSTEM_INFO_UID.last + 1).reversedArray()
        hidden += Hidden(SYSTEM_INFO_UID, uidMarker(shown))
    }
    if (exchange.pass == ProbePass.VENDOR && exchange.command.contentEquals(DEVICE_INFO)) hidden += deviceIdHidden(reply)
    val text = hexHiding(reply, 0, minOf(reply.size, REPLY_SHOWN_BYTES), hidden)
    return if (reply.size > REPLY_SHOWN_BYTES) "$text … (${reply.size} bytes)" else text
}

/**
 * A data read's reply as its status (NFC-V's flags byte) and how many data
 * bytes followed, which the probe keeps as its memory; null for a reply that
 * isn't data, such as a NAK or an error reply, which is shown as it is.
 */
private fun dataReadText(step: ProbeStep, reply: ByteArray): String? = when {
    step !in DATA_READS -> null
    // Type 2: 16 data bytes, no status.
    step == ProbeStep.READ_PAGES && reply.size == 16 -> "${reply.size} data bytes (see Memory)"
    // NFC-V: the flags byte, error flag clear, then the block.
    step == ProbeStep.READ_BLOCKS && reply.size >= 2 && reply[0].toInt() and 0x01 == 0 ->
        "${reply.copyOfRange(0, 1).toHex()} + ${reply.size - 1} data bytes (see Memory)"
    else -> null
}

/**
 * The values of the [DEVICE_ID_TLVS] in a [DEVICE_INFO] reply: one-byte tag,
 * one-byte length and value, to the end of the data, then `90 00` if the tag
 * accepted it. A two-byte reply is a status word alone. Data that doesn't
 * parse exactly as TLVs is hidden whole, since a serial could be anywhere in it.
 */
private fun deviceIdHidden(reply: ByteArray): List<Hidden> {
    if (reply.size == 2) return emptyList()
    val end = if (isOk(reply)) reply.size - 2 else reply.size
    val hidden = mutableListOf<Hidden>()
    var at = 0
    while (at < end) {
        val valueStart = at + 2
        val valueEnd = if (valueStart <= end) valueStart + (reply[at + 1].toInt() and 0xFF) else end + 1
        if (valueEnd > end) return listOf(Hidden(0 until end, "[$end bytes hidden]"))
        val tag = reply[at].toInt() and 0xFF
        if (tag in DEVICE_ID_TLVS && valueEnd > valueStart) {
            val value = reply.copyOfRange(valueStart, valueEnd)
            hidden += Hidden(valueStart until valueEnd, "[${reply.copyOfRange(at, at + 1).toHex()} ${shortUid(value)}]")
        }
        at = valueEnd
    }
    return hidden
}

private fun isOk(reply: ByteArray) =
    reply.size >= 2 && reply[reply.size - 2] == 0x90.toByte() && reply[reply.size - 1] == 0x00.toByte()
