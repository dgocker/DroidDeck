package com.droiddeck.launcher.diagnostics

import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A span of wall time, in Unix milliseconds. */
data class TimeWindow(val startMs: Long, val endMs: Long) {
    operator fun contains(ms: Long) = ms in startMs..endMs
    fun widen(beforeMs: Long, afterMs: Long) = TimeWindow(startMs - beforeMs, endMs + afterMs)
}

/** What a slice wrote: lines kept, bytes written, and whether it stopped at its cap. */
data class SliceResult(val lines: Int, val bytes: Long, val capped: Boolean)

/**
 * The part of a session-long log that belongs to one game launch, picked by the time stamped on
 * each line - the compositor's `HH:MM:SS.mmm` (wayland.log) and logcat's `MM-DD HH:MM:SS.mmm`
 * (app.log), both in the device's local time. A line without a stamp (a continuation) goes with the
 * line before it. Logs are read as streams and written up to a cap, so a log of any size costs a
 * bounded amount of memory and disk.
 */
object LogSlicer {
    private val COMPOSITOR = Regex("""^(\d{2}):(\d{2}):(\d{2})\.(\d{3}) """)
    private val LOGCAT = Regex("""^(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3}) """)

    /**
     * A compositor line's time as Unix ms: the stamp has no date, so it is the day (the window's,
     * the one before or the one after) that puts it nearest the window - a launch across midnight
     * keeps both halves.
     */
    fun compositorTime(line: String, near: TimeWindow, zone: ZoneId): Long? {
        val m = COMPOSITOR.find(line) ?: return null
        val (h, min, s, ms) = m.destructured
        val time = try { LocalTime.of(h.toInt(), min.toInt(), s.toInt(), ms.toInt() * 1_000_000) } catch (e: Exception) { return null }
        val day = Instant.ofEpochMilli(near.startMs).atZone(zone).toLocalDate()
        return nearest(listOf(day.minusDays(1), day, day.plusDays(1)).map { it.atTime(time).atZone(zone).toInstant().toEpochMilli() }, near)
    }

    /** A logcat threadtime line's time as Unix ms; the year is the one that puts it nearest the window. */
    fun logcatTime(line: String, near: TimeWindow, zone: ZoneId): Long? {
        val m = LOGCAT.find(line) ?: return null
        val v = m.groupValues.drop(1).map { it.toInt() }
        val year = Instant.ofEpochMilli(near.startMs).atZone(zone).year
        val candidates = (year - 1..year + 1).mapNotNull { y ->
            try { LocalDate.of(y, v[0], v[1]).atTime(v[2], v[3], v[4], v[5] * 1_000_000).atZone(zone).toInstant().toEpochMilli() }
            catch (e: Exception) { null }
        }
        return if (candidates.isEmpty()) null else nearest(candidates, near)
    }

    private fun nearest(candidates: List<Long>, w: TimeWindow): Long =
        candidates.minByOrNull { t -> if (t in w) 0L else minOf(Math.abs(t - w.startMs), Math.abs(t - w.endMs)) }!!

    /** [src]'s lines whose time ([time]) falls in [window], into [dst], up to [cap] bytes. Null when [src] cannot be read. */
    fun slice(src: File, dst: File, window: TimeWindow, cap: Long, time: (String) -> Long?): SliceResult? {
        if (!src.isFile) return null
        var lines = 0
        var bytes = 0L
        var capped = false
        var keeping = false
        try {
            dst.bufferedWriter().use { out ->
                src.bufferedReader().useLines { all ->
                    for (line in all) {
                        val t = time(line)
                        if (t != null) {
                            if (t > window.endMs) break
                            keeping = t in window
                        }
                        if (!keeping) continue
                        val size = line.toByteArray(Charsets.UTF_8).size + 1L
                        if (bytes + size > cap) {
                            capped = true
                            break
                        }
                        out.write(line)
                        out.write("\n")
                        bytes += size
                        lines++
                    }
                }
            }
        } catch (e: IOException) {
            dst.delete()
            throw e
        }
        if (lines == 0) dst.delete()
        return SliceResult(lines, bytes, capped)
    }

    /** [src]'s lines that [keep] accepts, into [dst], up to [cap] bytes; [dst] is not left behind empty. */
    fun filter(src: File, dst: File, cap: Long, keep: (String) -> Boolean): SliceResult? {
        if (!src.isFile) return null
        var lines = 0
        var bytes = 0L
        var capped = false
        try {
            dst.bufferedWriter().use { out ->
                src.bufferedReader().useLines { all ->
                    for (line in all) {
                        if (!keep(line)) continue
                        val size = line.toByteArray(Charsets.UTF_8).size + 1L
                        if (bytes + size > cap) {
                            capped = true
                            break
                        }
                        out.write(line)
                        out.write("\n")
                        bytes += size
                        lines++
                    }
                }
            }
        } catch (e: IOException) {
            dst.delete()
            throw e
        }
        if (lines == 0) dst.delete()
        return SliceResult(lines, bytes, capped)
    }
}
