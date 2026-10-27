package io.github.cacack.keginker.diagnostics

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The folder in the app's container that Debug builds save each shown report
 * to, for a developer to pull off the phone (see CONTRIBUTING.md). Release
 * builds save nothing.
 */
const val SAVED_REPORTS_FOLDER = "reports"

/**
 * The file a Debug build saves a report titled [title] under, shown [at]: the
 * title in lower case with dashes, then the UTC time, such as
 * `display-report-2026-10-26T14-22-33.123Z.txt`. No colons, so `adb` and
 * `devicectl` paths need no quoting.
 */
fun savedReportFileName(title: String, at: Instant): String {
    val slug = title.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.joinToString("-")
    return "$slug-${at.toString().replace(':', '-')}.txt"
}

/** [savedReportFileName] for a report shown now. */
fun savedReportFileName(title: String): String = savedReportFileName(title, Clock.System.now())
