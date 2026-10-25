package io.github.cacack.keginker.diagnostics

/** The "New display report" issue form, `.github/ISSUE_TEMPLATE/display-report.yml`. */
private const val NEW_DISPLAY_ISSUE = "https://github.com/cacack/keginker/issues/new?template=display-report.yml"

/** Longest link that carries the report; GitHub and browsers reject much longer ones. */
internal const val MAX_ISSUE_URL_LENGTH = 8_000

/**
 * A link that opens the "New display report" issue form in the browser.
 * [includesReport] says whether the report is already filled in; when it's
 * too long to fit, the user pastes it instead.
 */
class IssueLink(val url: String, val includesReport: Boolean) {
    /** What the link does, shown under its button on both platforms. */
    val caption: String = if (includesReport) {
        "Opens the New display report form in your browser with this report filled in. Add a photo and the model printed on the display. $PUBLIC_NOTE"
    } else {
        "Opens the New display report form in your browser and copies this report, which is too long to fill in, so you can paste it. $PUBLIC_NOTE"
    }

    /**
     * Whether Share sends the report as a [REPORT_FILE_NAME] file rather than
     * as text: when the link couldn't include it, it may be too long for a
     * share target to take as text too.
     */
    val sharesReportAsFile: Boolean get() = !includesReport
}

/**
 * The one name a shared report file has, whatever the report, so each share
 * replaces the last one's file. Both platforms write it to a `reports`
 * folder they empty first.
 */
const val REPORT_FILE_NAME = "keginker-report.txt"

private const val PUBLIC_NOTE = "GitHub issues are public: anyone can read the report once you submit it."

/** The issue link for a display [report] from [formatDisplayReport]. */
fun displayReportIssueLink(report: String): IssueLink {
    val withReport = "$NEW_DISPLAY_ISSUE&report=${percentEncode(report)}"
    return if (withReport.length <= MAX_ISSUE_URL_LENGTH) IssueLink(withReport, true) else IssueLink(NEW_DISPLAY_ISSUE, false)
}

/** RFC 3986 percent-encoding of [text] as UTF-8, leaving only unreserved characters as they are. */
internal fun percentEncode(text: String): String = buildString {
    for (byte in text.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c.toChar() in "-._~") {
            append(c.toChar())
        } else {
            append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
        }
    }
}

private const val HEX = "0123456789ABCDEF"
