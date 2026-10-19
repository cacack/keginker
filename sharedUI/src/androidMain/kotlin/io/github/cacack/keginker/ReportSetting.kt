package io.github.cacack.keginker

import android.content.Context

/** Whether a successful write offers its diagnostics report; saved across restarts. */
class ReportSetting(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var offerAfterSuccess: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(value) = prefs.edit().putBoolean(KEY, value).apply()

    private companion object {
        const val KEY = "offer_report_after_success"
    }
}
