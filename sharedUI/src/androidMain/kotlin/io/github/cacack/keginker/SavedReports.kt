package io.github.cacack.keginker

import android.util.Log
import io.github.cacack.keginker.diagnostics.SAVED_REPORTS_FOLDER
import io.github.cacack.keginker.diagnostics.savedReportFileName
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val LOG_TAG = "KegInkerReports"

/**
 * Debug builds' copy of each shown report, saved as text in
 * [SAVED_REPORTS_FOLDER] under [dir] for a developer to pull with `adb`
 * (see CONTRIBUTING.md). The app passes its no-backup folder, so the copies
 * stay on the phone. [save] writes off the main thread, in [scope], and logs
 * a failed write rather than throwing.
 */
class SavedReports(dir: File, private val scope: CoroutineScope) {
    private val folder = File(dir, SAVED_REPORTS_FOLDER)

    fun save(title: String, report: String): Job = scope.launch(Dispatchers.IO) {
        try {
            folder.mkdirs()
            File(folder, savedReportFileName(title)).writeText(report)
        } catch (e: IOException) {
            Log.w(LOG_TAG, "Couldn't save the report: ${e.message}")
        }
    }
}
