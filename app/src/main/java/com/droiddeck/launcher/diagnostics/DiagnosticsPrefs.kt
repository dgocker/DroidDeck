package com.droiddeck.launcher.diagnostics

import android.content.Context

/**
 * Game diagnostics' settings: on or off, and how much detail. A detail level sets both the event
 * log's level and how much of each game's stderr the launcher copies; neither reaches the game.
 */
object DiagnosticsPrefs {
    enum class Detail(val id: String, val level: Severity, val outputBytes: Long) {
        MINIMAL("minimal", Severity.WARN, 8L * 1024 * 1024),
        NORMAL("normal", Severity.INFO, 32L * 1024 * 1024),
        VERBOSE("verbose", Severity.DEBUG, 128L * 1024 * 1024);

        companion object {
            fun of(id: String?): Detail = entries.firstOrNull { it.id == id } ?: NORMAL
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("game_diagnostics", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("enabled", on).apply()
    }

    fun detail(context: Context): Detail = Detail.of(prefs(context).getString("detail", null))

    fun setDetail(context: Context, detail: Detail) {
        prefs(context).edit().putString("detail", detail.id).apply()
        DiagnosticsManager.applyDetail(detail)
    }
}
