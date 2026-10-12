package com.droiddeck.launcher.diagnostics

import java.io.File
import java.time.ZoneId

/**
 * performance.csv: the compositor's 10-second summaries (waylandcomp/src/compositor.c - the `stats`,
 * `perf` and `pacing` lines; tools/pacing_report.py reads the same ones) for a launch's time window,
 * one row per window. These are the frames the DroidDeck compositor received and showed - gamescope's
 * output, the game's frames together with anything drawn over them - not the game's own frame
 * times. A window's row is stamped with the time it was logged, the end of its 10 s; the first row
 * may begin before the game did.
 */
object PerformanceCsv {
    val COLUMNS = listOf(
        "window_end_utc", "seconds_since_launch", "onscreen_fps",
        "frames_committed", "commit_p50_ms", "commit_p95_ms", "commit_p99_ms", "commit_max_ms", "commit_hitches",
        "frames_shown", "never_shown", "shown_p50_ms", "shown_p95_ms", "shown_p99_ms", "shown_max_ms", "shown_hitches",
        "present_latency_p50_ms", "present_latency_p95_ms", "present_latency_max_ms",
        "panel_hz", "vsync_ms", "fps_limit", "layer_mode",
        "render_avg_ms", "render_max_ms", "fence_wait_avg_ms", "fence_wait_max_ms", "copy_scenes", "zero_copy_frames",
        "gpu_wait_timeouts",
    )

    private val STATS = Regex("""stats\s+last 10 s: (\d+) frames on screen \(([\d.]+) fps\)""")
    private val PACING = Regex(
        """pacing\s+last 10 s: (\d+) frames committed, interval p50/p95/p99/max ([\d.]+)/([\d.]+)/([\d.]+)/([\d.]+) ms, (\d+) at 2x p50 or longer \| """ +
            """(\d+) shown \((\d+) never shown\), interval p50/p95/p99/max ([\d.]+)/([\d.]+)/([\d.]+)/([\d.]+) ms, (\d+) at 2x p50 or longer \| """ +
            """commit -> present p50/p95/max ([\d.]+)/([\d.]+)/([\d.]+) ms""" +
            """(?: \| panel ([\d.]+) Hz nominal, vsync ([\d.]+) ms measured, fps limit (-?\d+), layer mode (on|off))?""",
    )
    private val PERF = Regex(
        """perf\s+last 10 s: \d+ ticks, \d+ scenes, \d+ on screen \(copy (\d+), zero-copy (\d+), layer copy \d+\) \| """ +
            """render_scene ([\d.]+)/([\d.]+) ms .*?fence wait ([\d.]+)/([\d.]+) ms \(\d+,""" +
            """(?:.*GPU waits timed out (\d+))?""",
    )

    class Result(val rows: Int)

    /**
     * Rows for the windows logged in [window] (widened by one window after its end, which still
     * covers its last seconds) from [lines] of a compositor log, into [dst]. Nothing is written when
     * there are no pacing or stats lines in it.
     */
    fun write(lines: Sequence<String>, dst: File, launchStartMs: Long, window: TimeWindow, zone: ZoneId): Result {
        val rows = LinkedHashMap<Long, Array<String>>()
        fun row(t: Long) = rows.getOrPut(t) { Array(COLUMNS.size) { "" } }
        var last: Long? = null
        for (line in lines) {
            val stamped = LogSlicer.compositorTime(line, window, zone)
            if (stamped == null || stamped !in window) continue
            // The three lines of one window are logged in the same tick; a few ms apart at most.
            val t = last?.takeIf { Math.abs(stamped - it) < 1000 } ?: stamped
            last = t
            STATS.find(line)?.let { m ->
                row(t)[COLUMNS.indexOf("onscreen_fps")] = m.groupValues[2]
            }
            PACING.find(line)?.let { m ->
                val g = m.groupValues
                val r = row(t)
                listOf("frames_committed", "commit_p50_ms", "commit_p95_ms", "commit_p99_ms", "commit_max_ms", "commit_hitches",
                    "frames_shown", "never_shown", "shown_p50_ms", "shown_p95_ms", "shown_p99_ms", "shown_max_ms", "shown_hitches",
                    "present_latency_p50_ms", "present_latency_p95_ms", "present_latency_max_ms",
                    "panel_hz", "vsync_ms", "fps_limit", "layer_mode").forEachIndexed { i, col -> r[COLUMNS.indexOf(col)] = g[i + 1] }
            }
            PERF.find(line)?.let { m ->
                val g = m.groupValues
                val r = row(t)
                r[COLUMNS.indexOf("copy_scenes")] = g[1]
                r[COLUMNS.indexOf("zero_copy_frames")] = g[2]
                r[COLUMNS.indexOf("render_avg_ms")] = g[3]
                r[COLUMNS.indexOf("render_max_ms")] = g[4]
                r[COLUMNS.indexOf("fence_wait_avg_ms")] = g[5]
                r[COLUMNS.indexOf("fence_wait_max_ms")] = g[6]
                r[COLUMNS.indexOf("gpu_wait_timeouts")] = g[7]
            }
        }
        val kept = rows.filter { (_, r) -> r[COLUMNS.indexOf("frames_committed")].isNotEmpty() || r[COLUMNS.indexOf("onscreen_fps")].isNotEmpty() }
        if (kept.isEmpty()) {
            dst.delete()
            return Result(0)
        }
        dst.bufferedWriter().use { out ->
            out.write(COLUMNS.joinToString(","))
            out.write("\n")
            for ((t, r) in kept) {
                r[0] = DiagEvent.iso(t)
                r[1] = String.format(java.util.Locale.US, "%.1f", (t - launchStartMs) / 1000.0)
                out.write(r.joinToString(","))
                out.write("\n")
            }
        }
        return Result(kept.size)
    }
}
