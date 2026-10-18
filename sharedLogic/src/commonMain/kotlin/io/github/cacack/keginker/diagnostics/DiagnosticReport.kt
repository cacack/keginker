package io.github.cacack.keginker.diagnostics

import io.github.cacack.keginker.waveshare.activity
import io.github.cacack.keginker.waveshare.toHex

/**
 * Most UID bytes a report shows, and never more than half the UID: enough to
 * tell tag families apart, not to identify one tag.
 */
private const val UID_SHOWN_BYTES = 3

/**
 * The plain-text diagnostics report a user reads and then chooses to share.
 * It holds no image data, and only the start of the tag's UID.
 */
fun formatDiagnosticReport(app: AppInfo, device: DeviceInfo, report: WriteReport): String = buildString {
    appendLine("KegInker diagnostics report")
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
    val tag = report.tag
    if (tag == null) {
        appendLine("Tag: no details captured")
    } else {
        appendLine("Tag")
        field("Technologies", tag.technologies.takeIf { it.isNotEmpty() }?.joinToString(", "))
        field("UID", shortUid(tag.uid))
        field("ATQA", tag.atqa?.toHex())
        field("SAK", tag.sak?.let { byteArrayOf(it.toByte()).toHex() })
        field("Historical bytes", tag.historicalBytes?.toHex())
    }
    appendLine()
    appendLine("Detected protocol: ${report.protocol}")
    appendLine("Panel: ${report.panel}")
    appendLine()
    appendLine("Result")
    field("Message", report.message)
    field("Step", report.step?.let { "${it.name} (${it.activity})" })
    field("Reason", report.reason)
    field("Cause", report.cause?.let(::describe))
    appendLine()
    exchanges(report.exchanges)
}

private fun StringBuilder.field(label: String, value: String?) {
    if (value != null) appendLine("  $label: $value")
}

private fun shortUid(uid: ByteArray): String {
    val shown = minOf(UID_SHOWN_BYTES, uid.size / 2)
    if (shown == 0) return "(${uid.size} bytes)"
    return "${uid.copyOfRange(0, shown).toHex()} … (${uid.size} bytes)"
}

/** One line per exchange; identical consecutive exchanges collapse into one line with ` × N`. */
private fun StringBuilder.exchanges(exchanges: List<Exchange>) {
    if (exchanges.isEmpty()) {
        appendLine("Exchanges: none")
        return
    }
    appendLine("Exchanges (${exchanges.size})")
    val runs = mutableListOf<Pair<Exchange, Int>>()
    for (exchange in exchanges) {
        val last = runs.lastOrNull()
        if (last != null && last.first.sameAs(exchange)) {
            runs[runs.lastIndex] = last.first to last.second + 1
        } else {
            runs += exchange to 1
        }
    }
    val timeWidth = runs.maxOf { "${it.first.atMs}".length }
    val stepWidth = runs.maxOf { it.first.step.name.length }
    for ((exchange, count) in runs) {
        val time = "${exchange.atMs}".padStart(timeWidth)
        val outcome = exchange.error?.let { "! $it" } ?: "< ${exchange.reply?.toHex().orEmpty()}"
        val repeat = if (count > 1) " × $count" else ""
        appendLine("  $time ms  ${exchange.step.name.padEnd(stepWidth)}  > ${commandText(exchange)}  $outcome$repeat")
    }
}

private fun commandText(exchange: Exchange): String =
    if (exchange.imageBytes == 0) exchange.command.toHex() else "${exchange.command.toHex()} … +${exchange.imageBytes} bytes image data"

private fun Exchange.sameAs(other: Exchange) =
    step == other.step &&
        command.contentEquals(other.command) &&
        imageBytes == other.imageBytes &&
        reply.contentEquals(other.reply) &&
        error == other.error
