package com.droiddeck.launcher.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** A clock the test moves by hand. */
class FakeClock(var wall: Long = 1_760_000_000_000L, var mono: Long = 5_000_000_000L) : DiagClock {
    override fun wallMs() = wall
    override fun monoNs() = mono
}

/**
 * Stands in for the guest's launcher (droiddeck-game-diag): writes the records it would write into
 * an inbox, for launches that never ran. No real game process anywhere.
 */
class FakeLauncher(val inbox: File) {
    private val seq = AtomicLong(1000)

    class Launch(val id: String, val inbox: File, val startMs: Long) {
        val output get() = File(inbox, "$id.out.log")
    }

    fun start(
        appId: String = "42", name: String? = "Test Game", launcher: String = "proton", startMs: Long = 1_760_000_000_000L,
        bootNs: Long = 9_000_000_000L, output: String? = null, extra: Map<String, Any?> = emptyMap(),
    ): Launch {
        inbox.mkdirs()
        val n = seq.incrementAndGet()
        val id = "20251009T090000Z-$n-abc$n"
        if (output != null) File(inbox, "$id.out.log").writeText(output)
        val json = JSONObject()
            .put("schema", 1).put("launch_id", id).put("app_id", appId).put("game_key", "steam-$appId")
            .put("name", name ?: JSONObject.NULL).put("name_source", if (name != null) "appmanifest" else "unknown")
            .put("launcher", launcher).put("verb", "waitforexitandrun").put("tool", "Proton - Experimental")
            .put("program", "proton").put("exe", "Game/Game.exe").put("args", JSONArray(listOf("-AUTH_PASSWORD=<redacted>", "-windowed")))
            .put("graphics", JSONObject().put("TU_DEBUG", JSONObject.NULL).put("DXVK_ASYNC", "1"))
            .put("started_unix_ms", startMs).put("started_boot_ns", bootNs).put("boot_id", "11111111-2222-3333-4444-555555555555")
            .put("wrapper_pid", 4000 + n).put("child_pid", 5000 + n).put("child_start_time", 777L)
            .put("output_file", if (output != null) "$id.out.log" else JSONObject.NULL)
        extra.forEach { (k, v) -> json.put(k, DiagEvent.jsonValue(v)) }
        File(inbox, "$id.start.json").writeText(json.toString())
        return Launch(id, inbox, startMs)
    }

    fun exit(
        launch: Launch, code: Int? = 0, signal: Int? = null, signalName: String? = null, durationMs: Long = 60_000,
        forwarded: List<String> = emptyList(), launchFailed: Boolean = false, coreDumped: Boolean = false,
        outputBytes: Long = launch.output.length(), capped: Boolean = false, sampled: Boolean = false,
    ) {
        val json = JSONObject()
            .put("schema", 1).put("launch_id", launch.id)
            .put("ended_unix_ms", launch.startMs + durationMs).put("ended_boot_ns", 9_000_000_000L + durationMs * 1_000_000)
            .put("duration_ms", durationMs).put("exited", code != null && signal == null)
            .put("exit_code", code ?: JSONObject.NULL).put("signal", signal ?: JSONObject.NULL)
            .put("signal_name", signalName ?: JSONObject.NULL).put("core_dumped", coreDumped)
            .put("launch_failed", launchFailed)
            .put("exec_errors", if (launchFailed) JSONArray().put(JSONObject().put("program", "proton").put("error", "[Errno 2] No such file").put("errno", 2)) else JSONArray())
            .put("forwarded_signals", JSONArray(forwarded))
            .put("output", JSONObject().put("bytes_seen", outputBytes).put("bytes_kept", outputBytes).put("capped", capped).put("complete", true).put("errors", JSONArray()))
            .put("samples", if (sampled) JSONObject().put("fex", 100).put("cpu", 100).put("interval_s", 5.0) else JSONObject.NULL)
        File(inbox, "${launch.id}.exit.json").writeText(json.toString())
    }
}

/** A compositor log line at [ms] in [zone], as droiddeck_log writes it. */
fun compositorLine(ms: Long, tag: String, text: String, zone: java.time.ZoneId): String {
    val t = java.time.Instant.ofEpochMilli(ms).atZone(zone)
    return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d  %-9s %s", t.hour, t.minute, t.second, t.nano / 1_000_000, tag, text)
}

fun pacingText(commits: Int = 600, p50: Double = 16.7, layer: String = "off") =
    "last 10 s: $commits frames committed, interval p50/p95/p99/max $p50/17.0/18.0/40.0 ms, 1 at 2x p50 or longer | " +
        "$commits shown (0 never shown), interval p50/p95/p99/max $p50/17.1/18.2/41.0 ms, 2 at 2x p50 or longer | " +
        "commit -> present p50/p95/max 4.1/8.0/12.3 ms | panel 120.0 Hz nominal, vsync 8.33 ms measured, fps limit 60, layer mode $layer"

fun perfText(copy: Int = 600) =
    "last 10 s: 1200 ticks, 600 scenes, 600 on screen (copy $copy, zero-copy 0, layer copy 0) | render_scene 1.20/3.40 ms | " +
        "base 0 black kept, 0 presented | acquire 0.10/0.50 ms | present 0.20/0.60 ms (600) | fence wait 0.80/2.50 ms (600, 0 GPU release waits) | " +
        "release 1.00/8.00 ms (590, 3 held) | 0 pool drops | GPU waits timed out 0, uses skipped 0, destroys deferred 0"

/** A logcat threadtime line at [ms] in [zone]. */
fun logcatLine(ms: Long, text: String, zone: java.time.ZoneId): String {
    val t = java.time.Instant.ofEpochMilli(ms).atZone(zone)
    return String.format(java.util.Locale.US, "%02d-%02d %02d:%02d:%02d.%03d  1234  1250 I %s", t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second, t.nano / 1_000_000, text)
}
