package com.droiddeck.launcher.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.BuildConfig
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Game diagnostics in the running app: hands the guest its inbox (BL_DIAG_DIR), watches the inbox
 * while a session runs, and turns each launch that ended into its folder ([SessionFinalizer]).
 *
 *  - A launch is taken on the first time it is seen: an `<id>.owner` file names this app run and
 *    the host session, and is touched every half minute while the game runs - the last time the
 *    app saw it alive, should the app die.
 *  - A launch with an exit record is finished 11 s after it ended (the compositor's 10 s summary
 *    that covers its last seconds is in by then), or at once when the session ends.
 *  - At the session's end ([sessionEnded], before its folder can go), a launch with no exit record
 *    and no live process is LOST; one whose launcher died while the session went on is UNKNOWN.
 *  - At the next app start ([recover]), a launch another app run took on and nobody finished is
 *    INTERRUPTED (the boot id says whether the device restarted); one still running is left.
 *
 * Everything runs on one background thread ("game-diag"); the inbox is polled every 2 s, a
 * directory listing of a few files, never on a thread that draws or a game thread.
 */
object DiagnosticsManager {
    private const val TAG = "GameDiagnostics"
    private const val POLL_MS = 2_000L
    private const val HEARTBEAT_MS = 30_000L
    private const val STALE_MS = 24L * 3600 * 1000

    private val runId = UUID.randomUUID().toString()
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "game-diag").apply { isDaemon = true }
    }
    @Volatile private var context: Context? = null
    @Volatile var store: DiagnosticsStore? = null
        private set
    @Volatile var log: EventLog? = null
        private set
    @Volatile private var host: File? = null
    @Volatile private var hostStartedMs = 0L
    private var watch: ScheduledFuture<*>? = null
    private val heartbeats = HashMap<String, Long>()
    private val deadSince = HashMap<String, Long>()
    private val announcedExit = HashSet<String>()
    val limits = DiagnosticsLimits()

    /** Bumped whenever the store changes, so a screen that lists it reloads. */
    var revision by mutableIntStateOf(0)
        private set

    /** Once per process, at app start: the store and the event log. Cheap; nothing is read. */
    @Synchronized
    fun init(context: Context) {
        if (store != null) return
        this.context = context.applicationContext
        val s = DiagnosticsStore(File(context.filesDir, "diagnostics"))
        if (!s.ensure()) Log.w(TAG, "could not create ${s.inbox}")
        store = s
        log = EventLog(s.eventsFile, DiagnosticsPrefs.detail(context).level, ringCapacity = 512)
    }

    fun applyDetail(detail: DiagnosticsPrefs.Detail) {
        log?.level = detail.level
    }

    /** An event from the app; never waits ([EventLog.post]). */
    fun record(source: String, severity: Severity, event: String, fields: Map<String, Any?> = emptyMap()) {
        log?.post(source, severity, event, fields)
    }

    /** The session's lifecycle events ([com.droiddeck.launcher.session.SessionEvents]), as diagnostic events. */
    fun mirror(event: String, fields: Map<String, Any?>) {
        val severity = when {
            event.contains("fail") || event.contains("error") -> Severity.ERROR
            event.contains("timeout") || event.contains("lost") -> Severity.WARN
            else -> Severity.INFO
        }
        record("session", severity, event, fields)
    }

    /** What the guest is started with: the inbox and the output cap, or nothing when diagnostics are off. */
    fun guestEnvironment(context: Context): List<String> {
        if (!DiagnosticsPrefs.enabled(context)) return emptyList()
        val s = store ?: return emptyList()
        if (!s.ensure()) return emptyList()
        return listOf("BL_DIAG_DIR=" + s.inbox.path, "BL_DIAG_OUTPUT_CAP=" + DiagnosticsPrefs.detail(context).outputBytes)
    }

    /** A host session started with its folder [dir]: watch the inbox until [sessionEnded]. */
    @Synchronized
    fun sessionStarted(dir: File) {
        host = dir
        hostStartedMs = System.currentTimeMillis()
        watch?.cancel(false)
        watch = executor.scheduleWithFixedDelay({ safely("poll") { poll(ended = false, hostDir = dir) } }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
        record("collector", Severity.INFO, "diagnostics.watching", mapOf("host_session" to dir.name, "run_id" to runId))
    }

    /**
     * The host session in [dir] has ended: every launch in it is finished now, while its logs are
     * still there (a session with logs off deletes its folder right after). The session's processes
     * are being torn down meanwhile, and a game told to stop writes its exit record on the way out,
     * so launches still running get up to [settleMs] to end first. Waits up to [timeoutMs] in all;
     * call it off the main thread.
     */
    fun sessionEnded(dir: File, settleMs: Long = 30_000, timeoutMs: Long = 90_000) {
        synchronized(this) {
            if (host == dir) {
                watch?.cancel(false)
                watch = null
                host = null
            }
        }
        val done = executor.submit {
            safely("session end") {
                val until = System.currentTimeMillis() + settleMs
                while (System.currentTimeMillis() < until && runningHere()) {
                    poll(ended = false, hostDir = dir)
                    Thread.sleep(500)
                }
                poll(ended = true, hostDir = dir)
                enforce()
            }
        }
        try {
            done.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "finishing the session's launches took too long; the next app start finishes them", e)
        }
        log?.flush(2_000)
    }

    /** A launch this app run took on is still running. */
    private fun runningHere(): Boolean {
        val s = store ?: return false
        return LaunchRecords.all(s.inbox).any { rec ->
            val start = rec.start() ?: return@any false
            rec.exit() == null && LaunchRecords.read(rec.ownerFile)?.optString("run_id") == runId && alive(start)
        }
    }

    /** At app start, with no session running: launches an earlier app run left unfinished. */
    fun recover() {
        executor.execute { safely("recover") { poll(ended = true, hostDir = null, recovering = true); cleanup(); enforce() } }
    }

    /**
     * The app is crashing ([com.droiddeck.launcher.session.CrashHandler]): the ring of recent events
     * goes to disk as it is, so a launch this run was watching gets its last events when the next
     * app start finishes it. Synchronous on purpose - nothing else will run after this.
     */
    fun crashed(reason: String) {
        val s = store ?: return
        val l = log ?: return
        try {
            l.post("app", Severity.ERROR, "app.crashed", mapOf("reason" to reason))
            File(s.root, "ring-$runId.jsonl").writeText(l.ring.snapshot().joinToString("") { it.toLine() + "\n" })
            l.flush(1_000)
        } catch (t: Throwable) {
            // Nothing more to do while the process dies.
        }
    }

    /** Launches started and not finished yet, for the history screen. */
    fun pending(): List<LaunchRecords.Start> {
        val s = store ?: return emptyList()
        return LaunchRecords.all(s.inbox).mapNotNull { it.start() }
    }

    /** Runs [work] on the diagnostics thread, then [done] on it with the result. */
    fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        executor.execute { done(runCatching(work)) }
    }

    fun changed() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { revision++ }
    }

    private fun poll(ended: Boolean, hostDir: File?, recovering: Boolean = false) {
        val s = store ?: return
        var finished = false
        for (rec in LaunchRecords.all(s.inbox)) {
            val start = rec.start() ?: continue
            var owner = LaunchRecords.read(rec.ownerFile)
            if (owner == null && !recovering) owner = adopt(rec, start, hostDir)
            val mine = owner?.optString("run_id") == runId
            val now = System.currentTimeMillis()
            var exit = rec.exit()
            val alive = exit == null && alive(start)
            // Gone with no record a moment ago: the record may have landed since.
            if (exit == null && !alive) exit = rec.exit()
            if (exit != null && announcedExit.add(rec.id)) record("guest", Severity.INFO, "game.exited", mapOf(
                "launch_id" to rec.id, "app_id" to start.appId, "exit_code" to exit.exitCode, "signal" to exit.signalName,
                "duration_ms" to exit.durationMs,
            ))
            if (alive || exit != null) deadSince.remove(rec.id)
            if (alive && mine && now - (heartbeats[rec.id] ?: 0L) >= HEARTBEAT_MS) {
                rec.ownerFile.setLastModified(now)
                heartbeats[rec.id] = now
            }
            val deadFor = if (alive || exit != null) 0L else now - deadSince.getOrPut(rec.id) { now }
            val decision = InboxPolicy.decide(mine, exit?.endedUnixMs, alive, deadFor, ended, now)
            if (decision.step == InboxPolicy.Step.FINISH) finished = finish(rec, start, owner, mine, decision.sessionEnded) || finished
        }
        if (finished) changed()
    }

    private fun adopt(rec: LaunchRecords, start: LaunchRecords.Start, sessionDir: File?): JSONObject {
        // A launch from before this host session (left running by an earlier app run) is not its.
        val hostDir = sessionDir?.takeIf { start.startedUnixMs <= 0 || start.startedUnixMs >= hostStartedMs - 60_000 }
            ?: hostFolderFor(start.startedUnixMs)
        val owner = JSONObject()
            .put("run_id", runId)
            .put("app_pid", android.os.Process.myPid())
            .put("host_session", hostDir?.name ?: JSONObject.NULL)
            .put("host_dir", hostDir?.path ?: JSONObject.NULL)
            .put("adopted_unix_ms", System.currentTimeMillis())
            .put("session", JSONObject(sessionInfo().mapValues { DiagEvent.jsonValue(it.value) }))
        try {
            val tmp = File(rec.ownerFile.path + ".tmp")
            tmp.writeText(owner.toString())
            if (!tmp.renameTo(rec.ownerFile)) rec.ownerFile.writeText(owner.toString())
        } catch (e: Exception) {
            Log.w(TAG, "could not take on launch ${rec.id}", e)
        }
        heartbeats[rec.id] = System.currentTimeMillis()
        record("guest", Severity.INFO, "game.started", mapOf(
            "launch_id" to rec.id, "app_id" to start.appId, "name" to start.name, "launcher" to start.launcher,
            "child_pid" to start.childPid,
        ))
        changed()
        return owner
    }

    private fun finish(rec: LaunchRecords, start: LaunchRecords.Start, owner: JSONObject?, mine: Boolean, sessionEnded: Boolean): Boolean {
        val s = store ?: return false
        val ctx = context ?: return false
        val hostDir = owner?.optStringOrNull("host_dir")?.let(::File) ?: hostFolderFor(start.startedUnixMs)
        val wayland = if (mine) com.droiddeck.launcher.wayland.WaylandCompositor.currentLogFile()?.takeIf { it.isFile }
            ?: hostDir?.let { File(it, "wayland.log") } else hostDir?.let { File(it, "wayland.log") }
        val bootNow = readFirstLine(File("/proc/sys/kernel/random/boot_id"))
        val state = LaunchState(
            processAlive = false,
            sessionEnded = sessionEnded,
            appRestarted = !mine,
            rebooted = bootNow != null && start.bootId != null && bootNow != start.bootId,
            lastSeenMs = rec.ownerFile.takeIf { it.isFile }?.lastModified() ?: 0L,
        )
        val sources = HostSources(
            hostSession = owner?.optStringOrNull("host_session"),
            waylandLog = wayland,
            appLog = hostDir?.let { File(it, "app.log") },
            appCrashTrace = hostDir?.let { File(it, "crash-app.txt") },
            crashBuffer = {
                val f = File(ctx.cacheDir, "diag-crash-buffer.txt")
                try {
                    com.droiddeck.launcher.core.SessionLogCapture.dumpCrashBuffer(f)
                    if (f.isFile) f.readText() else null
                } finally {
                    f.delete()
                }
            },
            events = { w -> log?.read { it.wallMs in w }.orEmpty() },
            ring = if (mine) log?.ring?.snapshot().orEmpty() else owner?.optStringOrNull("run_id")?.let { run ->
                File(s.root, "ring-$run.jsonl").takeIf { it.isFile }?.readLines()?.mapNotNull { DiagEvent.parse(it) }
            }.orEmpty(),
        )
        val info = SessionInfo(
            device = deviceInfo(),
            build = buildInfo(),
            session = owner?.optJSONObject("session")?.let { o -> o.keys().asSequence().associateWith { o.opt(it) } } ?: emptyMap(),
            collector = (log?.stats()?.toMap() ?: emptyMap()) + mapOf("run_id" to runId, "adopted_by_this_run" to mine),
            settings = mapOf("detail" to DiagnosticsPrefs.detail(ctx).id),
        )
        val summary = try {
            SessionFinalizer(s, limits.copy(outputBytes = DiagnosticsPrefs.detail(ctx).outputBytes)).finalize(rec, state, sources, info)
        } catch (e: Exception) {
            Log.w(TAG, "could not finish launch ${rec.id}; it is tried again later", e)
            null
        } ?: return false
        heartbeats.remove(rec.id)
        deadSince.remove(rec.id)
        announcedExit.remove(rec.id)
        record("collector", if (summary.status.problem) Severity.WARN else Severity.INFO, "game.diagnostics_saved", mapOf(
            "launch_id" to summary.id, "game" to summary.gameKey, "status" to summary.status.id, "bytes" to summary.bytes,
        ))
        Log.i(TAG, "launch ${summary.id} of ${summary.gameName}: ${summary.status.id} (${summary.summary})")
        return true
    }

    /** The pid and its start time both match: the process is the one the record names, not a reused number. */
    private fun alive(start: LaunchRecords.Start): Boolean {
        fun check(pid: Int, started: Long): Boolean {
            if (pid <= 1) return false
            val stat = readFirstLine(File("/proc/$pid/stat")) ?: return false
            if (started < 0) return true
            return stat.substringAfterLast(") ").split(' ').getOrNull(19)?.toLongOrNull() == started
        }
        return check(start.wrapperPid, start.json.optLong("wrapper_start_time", -1L)) || check(start.childPid, start.childStartTime)
    }

    private fun enforce() {
        val s = store ?: return
        val gone = s.enforce(limits)
        if (gone.isNotEmpty()) {
            Log.i(TAG, "deleted ${gone.size} old launch folder(s) to stay within the limits")
            changed()
        }
    }

    /** Leftovers no record leads to: half-built folders whose records are gone, torn temporary files, an output without a start. */
    private fun cleanup() {
        val s = store ?: return
        val now = System.currentTimeMillis()
        val inbox = s.inbox.listFiles().orEmpty()
        val ids = inbox.mapNotNull { f -> f.name.substringBefore(".start.json").takeIf { f.name.endsWith(".start.json") } }.toSet()
        inbox.filter { f -> now - f.lastModified() > STALE_MS && (f.name.endsWith(".tmp") || f.name.substringBefore('.') !in ids) }
            .forEach { it.delete() }
        val runs = inbox.filter { it.name.endsWith(".owner") }.mapNotNull { LaunchRecords.read(it)?.optStringOrNull("run_id") }.toSet()
        s.root.listFiles { f -> f.isFile && f.name.startsWith("ring-") && f.name.removePrefix("ring-").removeSuffix(".jsonl") !in runs }
            .orEmpty().forEach { it.delete() }
        s.root.listFiles { f -> f.isDirectory && f.name != DiagnosticsStore.INBOX }.orEmpty().forEach { game ->
            game.listFiles { f -> f.isDirectory && f.name.endsWith(DiagnosticsStore.PARTIAL) }.orEmpty()
                .filter { it.name.removeSuffix(DiagnosticsStore.PARTIAL) !in ids }
                .forEach { DiagnosticsStore.deleteTree(it) }
        }
    }

    /**
     * The session folder a launch nobody took on ran in, by time: the newest folder whose first
     * event is before the launch's start and whose session log was still written after it.
     */
    private fun hostFolderFor(startMs: Long): File? {
        val ctx = context ?: return null
        if (startMs <= 0) return null
        return com.droiddeck.launcher.session.SessionPaths.sessionFolders(ctx).asReversed().firstOrNull { dir ->
            val first = readFirstLine(File(dir, "events.jsonl"))?.let { runCatching { JSONObject(it).optLong("t", 0L) }.getOrNull() } ?: 0L
            val last = maxOf(File(dir, "session.log").lastModified(), File(dir, "events.jsonl").lastModified())
            first in 1..startMs && startMs <= last + 60_000
        }
    }

    private fun sessionInfo(): Map<String, Any?> {
        val ctx = context
        val state = com.droiddeck.launcher.session.SessionState
        val driverId = ctx?.let { com.droiddeck.launcher.session.SessionPrefs.linuxDriver(it) }.orEmpty()
        val driverName = if (driverId.isEmpty()) "the runtime's own Turnip" else try {
            ctx?.let { com.droiddeck.launcher.gpu.LinuxVulkanDriverManager(it).getDriverName(driverId) } ?: driverId
        } catch (e: Exception) {
            driverId
        }
        return mapOf(
            "mode" to state.mode,
            "fps_limit" to state.fpsLimit,
            "refresh_hz" to state.refreshHz,
            "hdr" to state.hdr,
            "layer_mode" to com.droiddeck.launcher.wayland.CompositorHost.layerMode?.let { if (it) "on" else "off" },
            "vulkan_driver" to driverName,
            "logs_enabled" to ctx?.let { com.droiddeck.launcher.session.SessionPrefs.logsEnabled(it) },
        )
    }

    private fun deviceInfo(): Map<String, Any?> = mapOf(
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "device" to Build.DEVICE,
        "soc_manufacturer" to if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else null,
        "soc_model" to if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null,
        "hardware" to Build.HARDWARE,
        "android_release" to Build.VERSION.RELEASE,
        "sdk_int" to Build.VERSION.SDK_INT,
        "security_patch" to Build.VERSION.SECURITY_PATCH,
        "kernel" to System.getProperty("os.version"),
        "page_size" to try { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) } catch (e: Exception) { null },
        "abis" to Build.SUPPORTED_ABIS.toList(),
    )

    private fun buildInfo(): Map<String, Any?> = mapOf(
        "version_name" to BuildConfig.VERSION_NAME,
        "version_code" to BuildConfig.VERSION_CODE,
        "build_type" to BuildConfig.BUILD_TYPE,
        "build_label" to BuildConfig.BUILD_LABEL,
        "source_commit" to BuildConfig.SOURCE_COMMIT,
        "ci_build" to BuildConfig.CI_BUILD,
    )

    private fun readFirstLine(f: File): String? = try {
        f.bufferedReader().use { it.readLine() }?.trim()?.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private inline fun safely(what: String, work: () -> Unit) {
        try {
            work()
        } catch (t: Throwable) {
            Log.w(TAG, "$what failed", t)
        }
    }
}
