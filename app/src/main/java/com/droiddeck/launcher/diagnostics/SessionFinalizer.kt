package com.droiddeck.launcher.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.ZoneId

/** What the host session (the proot session the game ran in) has that belongs to a launch. */
class HostSources(
    /** The host session folder's name, for the manifest. */
    val hostSession: String? = null,
    /** The compositor's session log (wayland.log). */
    val waylandLog: File? = null,
    /** The app's own logcat (app.log). */
    val appLog: File? = null,
    /** The app's own crash trace (crash-app.txt), if the app crashed. */
    val appCrashTrace: File? = null,
    /** Android's crash buffer as text (logcat -b crash), read only for a launch that ended badly. */
    val crashBuffer: (() -> String?)? = null,
    /** The app's diagnostic events in a time window. */
    val events: (TimeWindow) -> List<DiagEvent> = { emptyList() },
    /** The in-memory ring of recent events, when the app run that watched the launch is this one. */
    val ring: List<DiagEvent> = emptyList(),
)

/** What the app knows about a launch beyond its records. */
class LaunchState(
    val processAlive: Boolean = false,
    val sessionEnded: Boolean = false,
    val appRestarted: Boolean = false,
    val rebooted: Boolean = false,
    /** When the app last saw the launch running (its owner file's time); 0 if never. */
    val lastSeenMs: Long = 0L,
    val nowMs: Long = System.currentTimeMillis(),
)

/** Device, build and setup facts for the manifest, gathered by the app. */
class SessionInfo(
    val device: Map<String, Any?> = emptyMap(),
    val build: Map<String, Any?> = emptyMap(),
    /** What the app chose for the session: compositor layer mode, refresh, fps cap, Vulkan driver, HDR. */
    val session: Map<String, Any?> = emptyMap(),
    /** The collector's own counters (EventLog.Stats). */
    val collector: Map<String, Any?> = emptyMap(),
    val settings: Map<String, Any?> = emptyMap(),
)

/**
 * Turns one launch's inbox records into its diagnostics folder, `<game>/<launch>/`:
 *
 *  - manifest.json      schema, game, times, status, device, build, configuration, files, what is missing and why
 *  - process_exit.json  the guest's exit record as written, and the verdict on it ([ExitClassifier])
 *  - session.log        this launch's diagnostic events, one JSON object a line ([DiagEvent])
 *  - wine.log / game.log  the game's stderr as the launcher copied it (a Proton launch / a Linux one)
 *  - fex.log, vulkan.log  the lines of that output naming FEX, and Vulkan or the driver ([OutputScan])
 *  - wayland.log, android.log  the compositor's and the app's log lines in the launch's time window
 *  - performance.csv    the compositor's 10 s summaries in that window ([PerformanceCsv])
 *  - crash/             only real artifacts, only for a launch that ended badly: the crash report
 *                       in the game's output, Android's crash buffer lines in the window, the app's
 *                       own crash trace, the last events in memory. Never a memory dump.
 *
 * A source that is not there is not faked: it is left out and named under "missing" with the
 * reason. A step that fails (a read error, a full disk) is named under "warnings" and the rest
 * still gets written. The folder is built as `<launch>.partial` and renamed when the manifest is
 * in; the inbox records go only after that, so a finalize cut short is done again from them.
 */
class SessionFinalizer(
    private val store: DiagnosticsStore,
    private val limits: DiagnosticsLimits = DiagnosticsLimits(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val freeBytes: (File) -> Long = { it.usableSpace },
) {
    /** The folder made for [records], or null when there is nothing to make yet (no start record, or still running). */
    fun finalize(records: LaunchRecords, state: LaunchState, sources: HostSources, info: SessionInfo): SessionSummary? {
        val start = records.start() ?: return null
        val exit = records.exit()
        if (exit == null && state.processAlive) return null
        val key = start.gameKey
        val id = start.launchId.ifEmpty { records.id }
        val final = store.sessionDir(key, id)
        if (final.isDirectory) {
            cleanInbox(records)
            return store.session(key, id)
        }
        val dir = store.partialDir(key, id)
        dir.mkdirs()
        val warnings = ArrayList<String>()
        val missing = LinkedHashMap<String, String>()
        val described = LinkedHashMap<String, String>()
        val truncated = HashSet<String>()

        val startMs = start.startedUnixMs.takeIf { it > 0 } ?: records.startFile.lastModified()
        val lastSeen = maxOf(state.lastSeenMs, records.outputFile.lastModified(), records.startFile.lastModified())
        val endMs = exit?.endedUnixMs?.takeIf { it > 0 } ?: when {
            state.sessionEnded && !state.appRestarted -> state.nowMs
            else -> lastSeen.coerceAtLeast(startMs)
        }
        val window = TimeWindow(startMs, endMs)
        val lowDisk = freeBytes(dir) < limits.minFreeBytes
        if (lowDisk) warnings += "low disk space (under ${limits.minFreeBytes / (1024 * 1024)} MB free): only the records and the game's output were kept"

        // The game's stderr copy: moved in, never copied, then scanned once.
        val outputName = if (start.launcher == "proton") "wine.log" else "game.log"
        val output = File(dir, outputName)
        val outputRecord = exit?.output
        try {
            if (records.outputFile.isFile && !output.isFile) {
                if (!records.outputFile.renameTo(output)) records.outputFile.copyTo(output, overwrite = true)
            }
        } catch (e: Exception) {
            warnings += "the game's output could not be moved in: ${e.message}"
        }
        val emptyOutput = output.isFile && output.length() == 0L
        if (emptyOutput) output.delete()
        var scan: OutputScan.Result? = null
        if (output.isFile) {
            described[outputName] = "stderr of the game's process tree (Proton, Wine, DXVK/VKD3D-Proton, FEX, Mesa), copied by the launcher"
            if (outputRecord?.optBoolean("capped") == true) truncated += outputName
            if (outputRecord != null && !outputRecord.optBoolean("complete", true)) warnings += "a process the game left behind still held its output when the launcher exited; the end of $outputName may be missing"
            try {
                scan = OutputScan.scan(output, File(dir, "fex.log"), File(dir, "vulkan.log"), File(dir, "crash/$CRASH_REPORT"), limits.extractBytes)
            } catch (e: IOException) {
                warnings += "the game's output could not be read: ${e.message}"
            }
        } else {
            missing[outputName] = when {
                start.outputFile == null && outputRecord == null && exit != null -> "the launcher kept no copy of the game's output (BL_DIAG_OUTPUT_CAP=0 or the file could not be created)"
                outputRecord?.optJSONArray("errors")?.length()?.let { it > 0 } == true -> "the launcher could not copy the game's output: " + outputRecord.optJSONArray("errors")!!.optString(0)
                emptyOutput || outputRecord?.optLong("bytes_seen", -1L) == 0L -> "the game's process tree wrote nothing to stderr"
                else -> "no copy of the game's output was found"
            }
        }
        if (start.launcher == "proton") missing.putIfAbsent("game.log", "not used for a Proton launch: the game's output is wine.log")
        else missing.putIfAbsent("wine.log", "not a Wine launch (${start.launcher}): the game's output is game.log")
        if (scan != null) {
            if (scan.fexLines > 0) described["fex.log"] = "lines of $outputName that name FEX (${scan.fexLines})"
            else missing["fex.log"] = "no line of the game's output names FEX (FEX logs errors only by default)"
            if (scan.vulkanLines > 0) described["vulkan.log"] = "lines of $outputName about Vulkan, DXVK/VKD3D-Proton or the driver (${scan.vulkanLines})"
            else missing["vulkan.log"] = "no Vulkan or driver line in the game's output"
            if (File(dir, "fex.log").length() >= limits.extractBytes - 4096) truncated += "fex.log"
            if (File(dir, "vulkan.log").length() >= limits.extractBytes - 4096) truncated += "vulkan.log"
        } else {
            missing.putIfAbsent("fex.log", "no game output to take FEX lines from")
            missing.putIfAbsent("vulkan.log", "no game output to take Vulkan lines from")
        }

        // FEX's own counters (JIT time, SIGBUS, SMC, x87 fallbacks...) and the CPU's clocks, as the launcher
        // sampled them every 10 s: the JIT time per window tells compiling from running.
        val samples = exit?.json?.optJSONObject("samples")
        val counterHz = (info.device["counter_hz"] as? Number)?.toLong()?.takeIf { it > 0 }
        val every = samples?.optDouble("interval_s")?.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "%.0f", it) } ?: "10"
        takeSamples(records.fexStatsFile, File(dir, "fex_stats.csv"), counterHz, described, missing, warnings,
            "FEX's live statistics (FEXCore SHMStats) for each FEX process of the game, summed over its threads, every $every s" +
                if (counterHz != null) "; *_ms columns from the timer's $counterHz Hz" else "; times in timer ticks (the timer frequency was not known)",
            when {
                exit != null && samples == null -> "the launcher took no samples (BL_DIAG_SAMPLE_INTERVAL=0, or a launcher from before sampling)"
                start.launcher == "native" -> "not run under FEX"
                else -> "FEX wrote no statistics for the game's processes (ProfileStats off in its configuration, or /dev/shm not writable)"
            })
        takeSamples(records.cpuFile, File(dir, "cpu.csv"), null, described, missing, warnings,
            "each CPU's current clock and the hottest thermal zone, every $every s, as sysfs gave them",
            if (exit != null && samples == null) "the launcher took no samples" else "the kernel did not let the CPU clocks or thermal zones be read")

        // The verdict, with what the output says.
        val verdict = ExitClassifier.classify(ExitFacts(exit, scan?.crashEvidence, state.processAlive, state.sessionEnded, state.appRestarted, state.rebooted))
        if (!verdict.status.problem) File(dir, "crash/$CRASH_REPORT").delete()
        else if (File(dir, "crash/$CRASH_REPORT").isFile) described["crash/$CRASH_REPORT"] = "the crash report in $outputName and the ${OutputScan.CRASH_CONTEXT_LINES - 1} lines after it"

        // Slices of the host session's logs.
        val logWindow = window.widen(5_000, 11_000)
        if (lowDisk) {
            missing["wayland.log"] = "skipped: low disk space"
            missing["android.log"] = "skipped: low disk space"
        } else {
            slice(sources.waylandLog, File(dir, "wayland.log"), logWindow, { LogSlicer.compositorTime(it, logWindow, zone) },
                "the DroidDeck compositor's log lines from 5 s before the launch to 11 s after it", "the compositor's log", described, missing, truncated, warnings)
            slice(sources.appLog, File(dir, "android.log"), logWindow, { LogSlicer.logcatTime(it, logWindow, zone) },
                "DroidDeck's own logcat lines (this app's process only) from 5 s before the launch to 11 s after it", "the app's logcat capture (app.log)", described, missing, truncated, warnings)
        }
        // performance.csv from the compositor's own summaries.
        val perf = File(dir, "performance.csv")
        if (!lowDisk && sources.waylandLog?.isFile == true) {
            try {
                val rows = sources.waylandLog.bufferedReader().useLines { PerformanceCsv.write(it, perf, startMs, window.widen(0, 11_000), zone).rows }
                if (rows > 0) described["performance.csv"] = "the compositor's 10 s pacing/perf/stats summaries in the launch's window ($rows windows); frames as the compositor received and showed them, not the game's own frame times"
                else missing["performance.csv"] = "no compositor summary in the launch's window (one is logged every 10 s while frames arrive)"
            } catch (e: IOException) {
                perf.delete()
                missing["performance.csv"] = "the compositor's log could not be read: ${e.message}"
            }
        } else {
            missing["performance.csv"] = if (lowDisk) "skipped: low disk space" else "the compositor's log was not available"
        }

        // crash/: only real artifacts, only for a launch that ended badly.
        if (verdict.status.problem) {
            if (sources.ring.isNotEmpty()) {
                val tail = sources.ring.filter { it.wallMs <= endMs + 11_000 }.takeLast(200)
                if (tail.isNotEmpty()) write(File(dir, "crash/last-events.jsonl"), tail.joinToString("") { it.toLine() + "\n" }, warnings)
                    ?.let { described["crash/last-events.jsonl"] = "the app's last ${tail.size} diagnostic events in memory when the launch ended" }
            }
            sources.appCrashTrace?.takeIf { it.isFile && it.lastModified() in logWindow }?.let { trace ->
                try {
                    trace.copyTo(File(dir, "crash/app-crash.txt"), overwrite = true)
                    described["crash/app-crash.txt"] = "DroidDeck's own uncaught exception, written by its crash handler"
                } catch (e: IOException) {
                    warnings += "the app's crash trace could not be copied: ${e.message}"
                }
            }
            if (!lowDisk) {
                val buffer = try { sources.crashBuffer?.invoke() } catch (e: Exception) { warnings += "Android's crash buffer could not be read: ${e.message}"; null }
                val lines = buffer?.lineSequence()?.filter { line -> LogSlicer.logcatTime(line, logWindow, zone)?.let { it in logWindow } == true }?.toList().orEmpty()
                if (lines.isNotEmpty()) {
                    write(File(dir, "crash/android-crash-buffer.txt"), lines.joinToString("\n", postfix = "\n").take(1024 * 1024), warnings)
                        ?.let { described["crash/android-crash-buffer.txt"] = "Android's crash buffer (logcat -b crash) lines in the launch's window; any process on the device, not only the game" }
                }
            }
        }
        File(dir, "crash").takeIf { it.isDirectory && it.listFiles().isNullOrEmpty() }?.delete()

        // session.log: the guest's records and the app's events, as events.
        val events = ArrayList<DiagEvent>()
        events += DiagEvent(startMs, start.startedBootNs, "guest", Severity.INFO, "game.started", mapOf(
            "launch_id" to id, "app_id" to start.appId, "name" to start.name, "launcher" to start.launcher,
            "program" to start.json.optString("program"), "child_pid" to start.childPid, "stock_fallback" to start.json.optBoolean("stock_fallback"),
        ))
        try {
            events += sources.events(window.widen(5_000, 11_000))
        } catch (e: Exception) {
            warnings += "the app's events could not be read: ${e.message}"
        }
        if (exit != null) {
            events += DiagEvent(endMs, exit.endedBootNs, "guest", if (verdict.status.problem) Severity.WARN else Severity.INFO, "game.exited", mapOf(
                "exit_code" to exit.exitCode, "signal" to exit.signalName, "core_dumped" to exit.coreDumped,
                "forwarded_signals" to exit.forwardedSignals, "duration_ms" to exit.durationMs, "launch_failed" to exit.launchFailed,
            ))
        }
        events += DiagEvent(state.nowMs, 0L, "collector", if (verdict.status.problem) Severity.WARN else Severity.INFO, "game.classified", mapOf(
            "status" to verdict.status.id, "certainty" to verdict.certainty.id, "summary" to verdict.summary,
        ))
        val log = StringBuilder()
        var logCapped = false
        for (e in events.sortedWith(compareBy<DiagEvent> { it.wallMs }.thenBy { it.monoNs })) {
            val line = e.toLine()
            if (log.length + line.length + 1 > limits.extractBytes) { logCapped = true; break }
            log.append(line).append('\n')
        }
        write(File(dir, "session.log"), log.toString(), warnings)?.let {
            described["session.log"] = "this launch's diagnostic events: the guest's start and exit records, the app's events in the window, the verdict"
            if (logCapped) truncated += "session.log"
        }

        // process_exit.json
        val durationMs = exit?.durationMs?.takeIf { it >= 0 }
        val exitJson = JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("launch_id", id)
            .put("status", verdict.status.id)
            .put("certainty", verdict.certainty.id)
            .put("summary", verdict.summary)
            .put("evidence", JSONArray(verdict.evidence))
            .put("hints", JSONArray(verdict.hints))
            .put("exit_record_present", exit != null)
            .put("exit_record", exit?.json ?: JSONObject.NULL)
            .put("process", JSONObject()
                .put("launcher", start.launcher)
                .put("program", start.json.optString("program"))
                .put("wrapper_pid", start.wrapperPid)
                .put("child_pid", start.childPid))
            .put("duration_ms", durationMs ?: (endMs - startMs))
            .put("duration_source", if (durationMs != null) "boot clock, start to exit" else "estimate: start to the last time the launch was seen")
            .put("core_dump", JSONObject()
                .put("flag_in_wait_status", exit?.coreDumped ?: false)
                .put("collected", false)
                .put("note", "core files are never collected; the flag only says the kernel reported one"))
        write(File(dir, "process_exit.json"), exitJson.toString(1) + "\n", warnings)?.let { described["process_exit.json"] = "the launcher's exit record and the verdict on it" }

        // manifest.json, last.
        val files = JSONArray()
        dir.walkTopDown().filter { it.isFile && it.name != DiagnosticsStore.MANIFEST }.sortedBy { it.relativeTo(dir).path }.forEach { f ->
            val name = f.relativeTo(dir).path
            files.put(JSONObject()
                .put("name", name)
                .put("bytes", f.length())
                .put("sha256", sha256(f) ?: JSONObject.NULL)
                .put("source", described[name] ?: "")
                .put("truncated", name in truncated))
        }
        EXPECTED.forEach { name -> if (!File(dir, name).isFile && name !in missing) missing[name] = "not collected" }
        missing.keys.toList().forEach { if (File(dir, it).isFile) missing.remove(it) }
        if (truncated.isNotEmpty()) warnings += "cut at a size limit: ${truncated.sorted().joinToString()}"
        if (exit == null) warnings += "no exit record: how the game ended was not observed"
        val manifest = JSONObject()
            .put("schema", "droiddeck.game-diagnostics")
            .put("schema_version", SCHEMA_VERSION)
            .put("session_id", id)
            .put("game", JSONObject()
                .put("key", key)
                .put("app_id", start.appId ?: JSONObject.NULL)
                .put("name", start.name ?: JSONObject.NULL)
                .put("name_source", start.nameSource))
            .put("time", JSONObject()
                .put("start_utc", DiagEvent.iso(startMs))
                .put("start_unix_ms", startMs)
                .put("start_boot_ns", start.startedBootNs)
                .put("end_utc", if (exit != null) DiagEvent.iso(endMs) else JSONObject.NULL)
                .put("end_unix_ms", if (exit != null) endMs else JSONObject.NULL)
                .put("end_boot_ns", exit?.endedBootNs ?: JSONObject.NULL)
                .put("last_seen_utc", if (exit == null) DiagEvent.iso(endMs) else JSONObject.NULL)
                .put("duration_ms", durationMs ?: JSONObject.NULL)
                .put("timezone", zone.id))
            .put("status", JSONObject()
                .put("status", verdict.status.id)
                .put("problem", verdict.status.problem)
                .put("certainty", verdict.certainty.id)
                .put("summary", verdict.summary)
                .put("evidence", JSONArray(verdict.evidence))
                .put("hints", JSONArray(verdict.hints))
                .put("hang_detection", "not performed: nothing recorded tells a hung game from a slow one"))
            .put("build", JSONObject(info.build.mapValues { DiagEvent.jsonValue(it.value) }))
            .put("device", JSONObject(info.device.mapValues { DiagEvent.jsonValue(it.value) })
                .put("boot_id_at_start", start.bootId ?: JSONObject.NULL))
            .put("launch", JSONObject()
                .put("launcher", start.launcher)
                .put("verb", start.json.optString("verb"))
                .put("tool", start.json.opt("tool") ?: JSONObject.NULL)
                .put("program", start.json.optString("program"))
                .put("stock_fallback", start.json.optBoolean("stock_fallback"))
                .put("exe", start.json.opt("exe") ?: JSONObject.NULL)
                .put("args_redacted", start.json.optJSONArray("args") ?: JSONArray())
                .put("sync", start.json.opt("sync") ?: JSONObject.NULL)
                .put("sync_env", start.json.optJSONObject("sync_env") ?: JSONObject())
                .put("fex", start.json.optJSONObject("fex") ?: JSONObject.NULL)
                .put("guest_cpu_ids", start.json.optJSONObject("cpu_ids") ?: JSONObject.NULL))
            .put("graphics", JSONObject()
                .put("requested", start.json.optJSONObject("graphics") ?: JSONObject.NULL)
                .put("session", JSONObject(info.session.mapValues { DiagEvent.jsonValue(it.value) }))
                .put("observed", JSONObject((scan?.observed ?: emptyMap<String, String?>()).mapValues { DiagEvent.jsonValue(it.value) }))
                .put("observed_note", "as the game's output said it (DXVK/VKD3D-Proton lines); null where nothing said it"))
            .put("host_session", hostJson(sources))
            .put("files", files)
            .put("missing", JSONArray(missing.map { (n, r) -> JSONObject().put("name", n).put("reason", r) }))
            .put("warnings", JSONArray(warnings))
            .put("limits", JSONObject()
                .put("output_bytes", limits.outputBytes)
                .put("slice_bytes", limits.sliceBytes)
                .put("extract_bytes", limits.extractBytes)
                .put("sessions_per_game", limits.sessionsPerGame)
                .put("total_bytes", limits.totalBytes)
                .put("memory_dump", "never collected"))
            .put("collector", JSONObject(info.collector.mapValues { DiagEvent.jsonValue(it.value) }))
            .put("settings", JSONObject(info.settings.mapValues { DiagEvent.jsonValue(it.value) }))
            .put("privacy", "stored on this device only; nothing is uploaded. Export runs every text file through the log redactor.")
        if (write(File(dir, DiagnosticsStore.MANIFEST), manifest.toString(1) + "\n", warnings) == null) return null
        if (!dir.renameTo(final)) {
            warnings += "could not rename ${dir.name}"
            return null
        }
        cleanInbox(records)
        return store.session(key, id)
    }

    /**
     * A sample file from the inbox into the launch's folder, with a `<name>_ms` column after every
     * `<name>_ticks` one when the timer frequency is known. Missing or empty: named under "missing".
     */
    private fun takeSamples(
        src: File, dst: File, counterHz: Long?, described: MutableMap<String, String>, missing: MutableMap<String, String>,
        warnings: MutableList<String>, describe: String, why: String,
    ) {
        if (!src.isFile || src.length() == 0L) {
            if (!dst.isFile) missing[dst.name] = why
            return
        }
        try {
            dst.bufferedWriter().use { out ->
                src.bufferedReader().useLines { lines ->
                    var ticks = emptyList<Int>()
                    lines.forEachIndexed { i, line ->
                        val cells = line.split(',')
                        if (i == 0) ticks = cells.indices.filter { cells[it].endsWith("_ticks") && counterHz != null }
                        val extra = ticks.map { c ->
                            if (i == 0) cells[c].removeSuffix("_ticks") + "_ms"
                            else cells.getOrNull(c)?.toLongOrNull()?.let { String.format(java.util.Locale.US, "%.3f", it * 1000.0 / counterHz!!) }.orEmpty()
                        }
                        out.write((cells + extra).joinToString(","))
                        out.write("\n")
                    }
                }
            }
            src.delete()
            described[dst.name] = describe
        } catch (e: IOException) {
            dst.delete()
            missing[dst.name] = "could not be moved in: ${e.message}"
            warnings += "${dst.name}: ${e.message}"
        }
    }

    private fun hostJson(s: HostSources) = JSONObject()
        .put("id", s.hostSession ?: JSONObject.NULL)
        .put("compositor_log", s.waylandLog?.isFile == true)
        .put("app_log", s.appLog?.isFile == true)

    private fun slice(
        src: File?, dst: File, window: TimeWindow, time: (String) -> Long?, describe: String, what: String,
        sources: MutableMap<String, String>, missing: MutableMap<String, String>, truncated: MutableSet<String>, warnings: MutableList<String>,
    ) {
        if (src == null || !src.isFile) {
            missing[dst.name] = "$what was not available (session logs off or already deleted, or never written)"
            return
        }
        try {
            val r = LogSlicer.slice(src, dst, window, limits.sliceBytes, time)
            when {
                r == null -> missing[dst.name] = "$what could not be read"
                r.lines == 0 -> missing[dst.name] = "$what has no line in the launch's time window"
                else -> {
                    sources[dst.name] = describe
                    if (r.capped) truncated += dst.name
                }
            }
        } catch (e: IOException) {
            dst.delete()
            missing[dst.name] = "$what could not be sliced: ${e.message}"
            warnings += "${dst.name}: ${e.message}"
        }
    }

    private fun write(f: File, text: String, warnings: MutableList<String>): File? = try {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
        f
    } catch (e: IOException) {
        File(f.path + ".tmp").delete()
        warnings += "${f.name} could not be written: ${e.message}"
        null
    }

    private fun cleanInbox(records: LaunchRecords) {
        records.files().forEach { it.delete() }
    }

    private fun sha256(f: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: IOException) {
        null
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val CRASH_REPORT = "game-crash-report.txt"
        /** The files a launch's folder is expected to hold; any not there is named under "missing". */
        val EXPECTED = listOf("session.log", "android.log", "wayland.log", "fex.log", "vulkan.log", "performance.csv", "fex_stats.csv", "cpu.csv", "process_exit.json")
    }
}
