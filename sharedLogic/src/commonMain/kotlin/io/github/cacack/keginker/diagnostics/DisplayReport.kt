package io.github.cacack.keginker.diagnostics

import io.github.cacack.keginker.nfc.TagKind
import io.github.cacack.keginker.probe.ProbeResult
import io.github.cacack.keginker.probe.describeNdefRecord
import io.github.cacack.keginker.waveshare.toHex

/**
 * The plain-text report of a read-only display probe, laid out like
 * [formatDiagnosticReport] and shared the same way. Like it, it shows only
 * the start of the tag's UID.
 */
fun formatDisplayReport(app: AppInfo, device: DeviceInfo, result: ProbeResult): String = buildString {
    reportHeader("KegInker display report", app, device, result.tag)
    appendLine("Detected protocol: ${result.protocol}")
    appendLine()
    appendLine("Probe (read-only)")
    if (result.kind == TagKind.ISO_DEP && result.exchanges.isNotEmpty()) {
        field("Applications accepted", result.acceptedAids.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.toHex() } ?: "none")
    }
    field("Capability container", result.capabilityContainer?.toHex())
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
    field("Note", result.note)
    appendLine()
    exchangeLines(
        result.exchanges.map {
            ReportLine(
                atMs = it.atMs,
                step = it.step.name,
                command = it.command.toHex(),
                outcome = outcomeText(it.reply, it.error),
            )
        },
    )
}
