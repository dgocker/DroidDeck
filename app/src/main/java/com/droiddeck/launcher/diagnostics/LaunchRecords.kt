package com.droiddeck.launcher.diagnostics

import org.json.JSONObject
import java.io.File

/**
 * The guest's records of one game launch (tools/linuxfs/overlay/usr/local/bin/droiddeck-game-diag),
 * as they sit in the inbox: `<id>.start.json`, `<id>.exit.json` once it ended, `<id>.out.log` (the
 * game's stderr). The app adds `<id>.owner` when it takes a launch on, naming the app run that saw it.
 */
class LaunchRecords(val dir: File, val id: String) {
    val startFile get() = File(dir, "$id.start.json")
    val exitFile get() = File(dir, "$id.exit.json")
    val outputFile get() = File(dir, "$id.out.log")
    val ownerFile get() = File(dir, "$id.owner")
    /** FEX's counters for the game's processes, sampled by the launcher. */
    val fexStatsFile get() = File(dir, "$id.fex.csv")
    /** CPU clocks and the hottest thermal zone, sampled by the launcher. */
    val cpuFile get() = File(dir, "$id.cpu.csv")

    /** Everything of this launch in the inbox, records and output. */
    fun files(): List<File> = listOf(startFile, exitFile, outputFile, ownerFile, fexStatsFile, cpuFile,
        File(dir, "$id.start.json.tmp"), File(dir, "$id.exit.json.tmp"))

    fun start(): Start? = read(startFile)?.let { Start(it) }
    fun exit(): Exit? = read(exitFile)?.let { Exit(it) }

    class Start(val json: JSONObject) {
        val launchId: String = json.optString("launch_id")
        val appId: String? = json.optStringOrNull("app_id")
        val gameKey: String = json.optStringOrNull("game_key") ?: appId?.let { "app-$it" } ?: "unknown"
        val name: String? = json.optStringOrNull("name")
        val nameSource: String = json.optString("name_source", "unknown")
        val launcher: String = json.optString("launcher", "unknown")
        val startedUnixMs: Long = json.optLong("started_unix_ms", 0L)
        val startedBootNs: Long = json.optLong("started_boot_ns", 0L)
        val bootId: String? = json.optStringOrNull("boot_id")
        val childPid: Int = json.optInt("child_pid", -1)
        val childStartTime: Long = json.optLong("child_start_time", -1L)
        val wrapperPid: Int = json.optInt("wrapper_pid", -1)
        val outputFile: String? = json.optStringOrNull("output_file")
    }

    class Exit(val json: JSONObject) {
        val endedUnixMs: Long = json.optLong("ended_unix_ms", 0L)
        val endedBootNs: Long = json.optLong("ended_boot_ns", 0L)
        val durationMs: Long = json.optLong("duration_ms", -1L)
        val exited: Boolean = json.optBoolean("exited", false)
        val exitCode: Int? = if (json.isNull("exit_code") || !json.has("exit_code")) null else json.optInt("exit_code")
        val signal: Int? = if (json.isNull("signal") || !json.has("signal")) null else json.optInt("signal")
        val signalName: String? = json.optStringOrNull("signal_name")
        val coreDumped: Boolean = json.optBoolean("core_dumped", false)
        val launchFailed: Boolean = json.optBoolean("launch_failed", false)
        val forwardedSignals: List<String> = json.optJSONArray("forwarded_signals")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val execErrors: List<String> = json.optJSONArray("exec_errors")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { e -> "${e.optString("program")}: ${e.optString("error")}" } }
        }.orEmpty()
        val output: JSONObject? = json.optJSONObject("output")
    }

    companion object {
        private val START = Regex("""(.+)\.start\.json""")

        /** The launches in [dir], oldest first (ids begin with their UTC start). */
        fun all(dir: File): List<LaunchRecords> =
            dir.listFiles()?.mapNotNull { f -> START.matchEntire(f.name)?.groupValues?.get(1) }?.sorted()
                ?.map { LaunchRecords(dir, it) }.orEmpty()

        /** A record, or null when it is missing or torn; the guest writes through a rename, so torn is rare. */
        fun read(f: File): JSONObject? = try {
            if (f.isFile) JSONObject(f.readText()) else null
        } catch (e: Exception) {
            null
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
