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
        field("Historical bytes", tag.historicalBytes?.toHex())
    }
    appendLine()
}

internal fun StringBuilder.field(label: String, value: String?) {
    if (value != null) appendLine("  $label: $value")
}

private fun shortUid(uid: ByteArray): String {
    val shown = minOf(UID_SHOWN_BYTES, uid.size / 2)
    if (shown == 0) return "(${uid.size} bytes)"
    return "${uid.copyOfRange(0, shown).toHex()} … (${uid.size} bytes)"
}

/** One exchange as a report shows it. */
internal data class ReportLine(val atMs: Long, val step: String, val command: String, val outcome: String)

private fun reportLine(exchange: Exchange) = ReportLine(
    atMs = exchange.atMs,
    step = exchange.step.name,
    command = commandText(exchange),
    outcome = outcomeText(exchange.reply, exchange.error),
)

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
