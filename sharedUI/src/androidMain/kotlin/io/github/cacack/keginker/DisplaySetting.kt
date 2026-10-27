package io.github.cacack.keginker

import android.content.Context

/** The display labels are written to; saved across restarts by its enum name. */
class DisplaySetting(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** [LabelDisplay.WAVESHARE_2IN7] until one is chosen, or if the saved one no longer exists. */
    var display: LabelDisplay
        get() = LabelDisplay.entries.firstOrNull { it.name == prefs.getString(KEY, null) } ?: LabelDisplay.WAVESHARE_2IN7
        set(value) = prefs.edit().putString(KEY, value.name).apply()

    private companion object {
        const val KEY = "label_display"
    }
}
