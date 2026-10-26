package io.github.cacack.keginker.diagnostics

import io.github.cacack.keginker.nfc.TagInfo
import io.github.cacack.keginker.waveshare.REPLY_SHOWN_BYTES
import io.github.cacack.keginker.waveshare.activity
import io.github.cacack.keginker.waveshare.toHex
import io.github.cacack.keginker.waveshare.toShownHex

/**
 * Most UID bytes a report shows, and never more than half the UID: enough to
 * tell tag families apart, not to identify one tag.
 */
private const val UID_SHOWN_BYTES = 3

private const val UNAVAILABLE = "unavailable"

/**
 * The plain-text diagnostics report a user reads and then chooses to share.
 * It holds no image data, and only the start of the tag's UID.
 */
fun formatDiagnosticReport(app: AppInfo, device: DeviceInfo, report: WriteReport): String = buildString {
    reportHeader("KegInker diagnostics report", app, device, report.tag)
    appendLine("Detected protocol: ${report.protocol}")
    appendLine("Panel: ${report.panel}")
    if (report.outcome != Outcome.NOT_ATTEMPTED) {
        appendLine("Image: ${if (report.testPattern) "built-in test pattern" else "chosen image (not included)"}")
    }
    appendLine()
    appendLine("Result")
    field("Outcome", report.outcome.name.lowercase().replace('_', ' '))
    field("Message", report.message)
    field("Step", report.step?.let { "${it.name} (${it.activity})" })
    field("Reason", report.reason)
    field("Cause", report.cause?.let(::describe))
    appendLine()
    exchangeLines(report.exchanges.map(::reportLine))
}

/** The title, privacy line, app, device and tag sections that every report starts with. */
internal fun StringBuilder.reportHeader(title: String, app: AppInfo, device: DeviceInfo, tag: TagInfo?) {
    appendLine(title)
    appendLine("This report leaves your phone only if you share it.")
    appendLine()
    appendLine("App")
    field("Version", app.version)
    field("Build", app.buildType)
    field("Commit", app.commit)
    appendLine()
    appendLine("Device")
    field("Manufacturer", device.manufacturer)
    field("Model", device.model)
    field("OS", device.os)
    field("NFC", if (device.nfcEnabled) "on" else "off")
    appendLine()
    if (tag == null) {
        appendLine("Tag: no details captured")
    } else {
        appendLine("Tag")
        field("Technologies", tag.technologies.takeIf { it.isNotEmpty() }?.joinToString(", "))
        field("UID", shortUid(tag.uid))
        // Always shown, so a reader can tell a value the platform withholds (iOS) from a missing line.
        field("ATQA", tag.atqa?.toHex() ?: UNAVAILABLE)
        field("SAK", tag.sak?.let { byteArrayOf(it.toByte()).toHex() } ?: UNAVAILABLE)
        field("Historical bytes", tag.historicalBytes?.let { historicalBytesText(it, tag.uid) })
        tag.details.forEach { field(it.name, it.value) }
    }
    appendLine()
}

internal fun StringBuilder.field(label: String, value: String?) {
    if (value != null) appendLine("  $label: $value")
}

/** The start of [uid] and its length, as every report shows a UID. */
internal fun shortUid(uid: ByteArray): String {
    val shown = uidShownBytes(uid)
    if (shown == 0) return "(${uid.size} bytes)"
    return "${uid.copyOfRange(0, shown).toHex()} … (${uid.size} bytes)"
}

/** How many bytes of [uid] a report shows: at most [UID_SHOWN_BYTES], and never more than half. */
internal fun uidShownBytes(uid: ByteArray) = minOf(UID_SHOWN_BYTES, uid.size / 2)

/** Bytes a report shows as [marker] instead of their hex. */
internal class Hidden(val range: IntRange, val marker: String)

/** How a report shows [uid] wherever it hides it: its start and length, or "hidden" when the UID isn't known. */
internal fun uidMarker(uid: ByteArray?) = "[UID ${uid?.let(::shortUid) ?: "hidden"}]"

/** Where [uid] appears in [bytes], in either byte order. */
internal fun uidHidden(bytes: ByteArray, uid: ByteArray?): List<Hidden> {
    if (uid == null) return emptyList()
    val reversed = uid.reversedArray()
    val marker = uidMarker(uid)
    return (0..bytes.size - uid.size).filter { at ->
        val window = bytes.copyOfRange(at, at + uid.size)
        window.contentEquals(uid) || window.contentEquals(reversed)
    }.map { Hidden(it until it + uid.size, marker) }
}

/** Bytes [from] until [to] of [bytes] as hex, with each run of bytes inside a [hidden] range shown once, as the first one's marker. */
internal fun hexHiding(bytes: ByteArray, from: Int, to: Int, hidden: List<Hidden>): String {
    val parts = mutableListOf<String>()
    var at = from
    while (at < to) {
        val run = hidden.firstOrNull { at in it.range }
        if (run != null) {
            parts += run.marker
            // Past this run, including any hidden range that overlaps it.
            while (at < to && hidden.any { at in it.range }) at++
        } else {
            parts += bytes.copyOfRange(at, at + 1).toHex()
            at++
        }
    }
    return parts.joinToString(" ")
}

/**
 * The ATS [historical] bytes as hex, with any run that matches a contiguous
 * part of [uid], from anywhere in it and in either byte order, hidden once it
 * is longer than the UID line shows. Some tags (EZ Sign) put most of their
 * UID there.
 */
internal fun historicalBytesText(historical: ByteArray, uid: ByteArray): String {
    val hidden = mutableListOf<Hidden>()
    val marker = uidMarker(uid)
    val shown = uidShownBytes(uid)
    for (order in listOf(uid, uid.reversedArray())) {
        for (at in historical.indices) {
            for (from in order.indices) {
                // The longest run from here that matches the UID from its byte [from].
                var length = 0
                while (from + length < order.size && at + length < historical.size && historical[at + length] == order[from + length]) length++
                if (length > shown) hidden += Hidden(at until at + length, marker)
            }
        }
    }
    return hexHiding(historical, 0, historical.size, hidden)
}

/** One exchange as a report shows it. */
internal data class ReportLine(val atMs: Long, val step: String, val command: String, val outcome: String)

internal fun reportLine(exchange: Exchange) = ReportLine(
    atMs = exchange.atMs,
    step = exchange.step.name,
    command = commandText(exchange),
    outcome = outcomeText(exchange.reply, exchange.error) + durationText(exchange),
)

/** Exchanges that take at least this long show how long they took; failures always do. */
internal const val SLOW_EXCHANGE_MS = 1_000L

/**
 * How long [exchange] took, for a failure or a slow exchange; empty otherwise,
 * so the usual quick exchanges keep collapsing into one line.
 */
internal fun durationText(exchange: Exchange): String =
    if (exchange.error != null || exchange.durationMs >= SLOW_EXCHANGE_MS) " (after ${exchange.durationMs} ms)" else ""

/** What came back for one exchange: the reply, cut to [REPLY_SHOWN_BYTES], or the error. */
internal fun outcomeText(reply: ByteArray?, error: String?): String =
    error?.let { "! $it" } ?: "< ${reply?.toShownHex(REPLY_SHOWN_BYTES).orEmpty()}"

/** One line per exchange; identical consecutive exchanges collapse into one line with ` × N`. */
internal fun StringBuilder.exchangeLines(lines: List<ReportLine>) {
    if (lines.isEmpty()) {
        appendLine("Exchanges: none")
        return
    }
    appendLine("Exchanges (${lines.size})")
    val runs = mutableListOf<Pair<ReportLine, Int>>()
    for (line in lines) {
        val last = runs.lastOrNull()
        // Identical apart from when they were sent.
        if (last != null && last.first.copy(atMs = line.atMs) == line) {
            runs[runs.lastIndex] = last.first to last.second + 1
        } else {
            runs += line to 1
        }
    }
    val timeWidth = runs.maxOf { "${it.first.atMs}".length }
    val stepWidth = runs.maxOf { it.first.step.length }
    for ((line, count) in runs) {
        val time = "${line.atMs}".padStart(timeWidth)
        val repeat = if (count > 1) " × $count" else ""
        appendLine("  $time ms  ${line.step.padEnd(stepWidth)}  > ${line.command}  ${line.outcome}$repeat")
    }
}

private fun commandText(exchange: Exchange): String =
    if (exchange.imageBytes == 0) exchange.command.toHex() else "${exchange.command.toHex()} … +${exchange.imageBytes} bytes image data"
