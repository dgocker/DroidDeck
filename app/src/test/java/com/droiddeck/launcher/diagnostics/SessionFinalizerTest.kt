package com.droiddeck.launcher.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionFinalizerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private lateinit var store: DiagnosticsStore
    private lateinit var launcher: FakeLauncher
    private var free = Long.MAX_VALUE
    private lateinit var finalizer: SessionFinalizer
    private val t0 = 1_760_000_000_000L

    @Before
    fun setUp() {
        store = DiagnosticsStore(File(tmp.root, "diagnostics")).also { it.ensure() }
        launcher = FakeLauncher(store.inbox)
        finalizer = SessionFinalizer(store, DiagnosticsLimits(), zone) { free }
    }

    private fun manifest(s: SessionSummary) = JSONObject(File(s.dir, "manifest.json").readText())
    private fun missing(s: SessionSummary) = s.missing.toMap()

    private fun finish(launch: FakeLauncher.Launch, state: LaunchState = LaunchState(sessionEnded = true, nowMs = t0 + 3_600_000), sources: HostSources = HostSources()) =
        finalizer.finalize(LaunchRecords(store.inbox, launch.id), state, sources, SessionInfo(
            device = mapOf("model" to "V2501A", "soc_model" to "SM8850", "android_release" to "16", "page_size" to 4096),
            build = mapOf("version_name" to "0.3.2"),
        ))

    @Test
    fun aNormalExitBecomesAFolderWithItsRecordsAndOutput() {
        val launch = launcher.start(output = "info:  DXVK: v2.6.1\ninfo:  Found device: Turnip Adreno (TM) 840\nfixme:ntdll:stuff\n")
        launcher.exit(launch, code = 0, durationMs = 61_000)
        val s = finish(launch)!!
        assertEquals(LaunchStatus.EXITED, s.status)
        assertEquals("Test Game", s.gameName)
        assertEquals(61_000L, s.durationMs)
        assertEquals("steam-42", s.dir.parentFile!!.name)
        assertEquals(launch.id, s.dir.name)
        val names = s.files.map { it.first }.toSet()
        assertTrue(names.containsAll(listOf("wine.log", "vulkan.log", "process_exit.json", "session.log")))
        assertFalse(File(s.dir, "crash").exists())
        // Sources that were not there are named, with a reason, and not faked.
        val missing = missing(s)
        assertTrue(missing["fex.log"]!!.contains("names FEX"))
        assertTrue(missing["wayland.log"]!!.contains("not available"))
        assertTrue(missing.containsKey("performance.csv"))
        assertFalse(File(s.dir, "fex.log").exists())
        assertFalse(File(s.dir, "wayland.log").exists())
        // The inbox is emptied of this launch.
        assertEquals(emptyList<String>(), store.inbox.list()!!.toList())
        val m = manifest(s)
        assertEquals(1, m.getInt("schema_version"))
        assertEquals("v2.6.1", m.getJSONObject("graphics").getJSONObject("observed").getString("dxvk_version"))
        assertEquals("Turnip Adreno (TM) 840", m.getJSONObject("graphics").getJSONObject("observed").getString("vulkan_device"))
        assertEquals("SM8850", m.getJSONObject("device").getString("soc_model"))
        assertEquals(t0, m.getJSONObject("time").getLong("start_unix_ms"))
        assertEquals(t0 + 61_000, m.getJSONObject("time").getLong("end_unix_ms"))
        assertEquals("exited", m.getJSONObject("status").getString("status"))
        assertTrue(m.getJSONObject("launch").getJSONArray("args_redacted").toString().contains("<redacted>"))
    }

    @Test
    fun theManifestListsEveryFileWithItsSizeAndHash() {
        val launch = launcher.start(output = "hello\n")
        launcher.exit(launch, code = 0)
        val s = finish(launch)!!
        val files = manifest(s).getJSONArray("files")
        val listed = (0 until files.length()).map { files.getJSONObject(it) }
        val onDisk = s.dir.walkTopDown().filter { it.isFile && it.name != "manifest.json" }.map { it.relativeTo(s.dir).path }.toSet()
        assertEquals(onDisk, listed.map { it.getString("name") }.toSet())
        for (f in listed) {
            val file = File(s.dir, f.getString("name"))
            assertEquals(file.length(), f.getLong("bytes"))
            val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(sha, f.getString("sha256"))
        }
    }

    @Test
    fun launchesOfDifferentGamesAndRelaunchesStaySeparate() {
        val a1 = launcher.start(appId = "42", output = "first run of 42\n", startMs = t0)
        val b = launcher.start(appId = "77", name = "Other", output = "run of 77\n", startMs = t0 + 1000)
        val a2 = launcher.start(appId = "42", output = "second run of 42\n", startMs = t0 + 2000)
        listOf(a1, b, a2).forEach { launcher.exit(it, code = 0) }
        val s1 = finish(a1)!!
        val sb = finish(b)!!
        val s2 = finish(a2)!!
        assertNotEquals(s1.dir, s2.dir)
        assertEquals("first run of 42\n", File(s1.dir, "wine.log").readText())
        assertEquals("second run of 42\n", File(s2.dir, "wine.log").readText())
        assertEquals("run of 77\n", File(sb.dir, "wine.log").readText())
        assertEquals(listOf("steam-42", "steam-77").sorted(), store.games().map { it.key }.sorted())
        assertEquals(listOf(a2.id, a1.id), store.sessions("steam-42").map { it.id })
        assertEquals(2, store.games().first { it.key == "steam-42" }.sessions)
    }

    @Test
    fun anErrorExitWithACrashReportKeepsTheReportUnderCrash() {
        val launch = launcher.start(output = "fixme:x\nwine: Unhandled page fault on read access to 0000000000000000 at address 0000000140001234\nbacktrace line\n")
        launcher.exit(launch, code = 3)
        val s = finish(launch)!!
        assertEquals(LaunchStatus.CRASHED, s.status)
        assertEquals("evidence", s.certainty)
        val report = File(s.dir, "crash/game-crash-report.txt").readText()
        assertTrue(report.startsWith("wine: Unhandled page fault"))
        assertTrue(report.contains("backtrace line"))
        val exit = JSONObject(File(s.dir, "process_exit.json").readText())
        assertEquals(3, exit.getJSONObject("exit_record").getInt("exit_code"))
        assertFalse(exit.getJSONObject("core_dump").getBoolean("collected"))
    }

    @Test
    fun aForcedKillIsRecordedAsAmbiguous() {
        val launch = launcher.start(output = "x\n")
        launcher.exit(launch, code = null, signal = 9, signalName = "SIGKILL")
        val s = finish(launch, sources = HostSources(ring = listOf(DiagEvent(t0 + 59_000, 1, "app", Severity.WARN, "memory.low"))))!!
        assertEquals(LaunchStatus.KILLED, s.status)
        assertEquals("ambiguous", s.certainty)
        // The ring's last events go with a launch that ended badly.
        assertTrue(File(s.dir, "crash/last-events.jsonl").readText().contains("memory.low"))
    }

    @Test
    fun aLaunchStillRunningIsLeftInTheInbox() {
        val launch = launcher.start(output = "x\n")
        assertNull(finish(launch, LaunchState(processAlive = true)))
        assertTrue(File(store.inbox, "${launch.id}.start.json").isFile)
        assertTrue(store.games().isEmpty())
    }

    @Test
    fun anUnfinishedLaunchFoundAfterARestartIsInterruptedWithItsOutput() {
        val launch = launcher.start(output = "last words\n")
        val s = finish(launch, LaunchState(appRestarted = true, lastSeenMs = t0 + 30_000, nowMs = t0 + 999_999))!!
        assertEquals(LaunchStatus.INTERRUPTED, s.status)
        assertTrue(s.incomplete)
        assertTrue(s.warnings.any { it.contains("no exit record") })
        assertEquals("last words\n", File(s.dir, "wine.log").readText())
        val time = manifest(s).getJSONObject("time")
        assertTrue(time.isNull("end_unix_ms"))
        assertTrue(time.isNull("duration_ms"))
        assertFalse(time.isNull("last_seen_utc"))
        assertFalse(File(s.dir, "crash/last-events.jsonl").exists())
    }

    @Test
    fun aLaunchCutOffByTheSessionsEndIsLost() {
        val launch = launcher.start()
        val s = finish(launch, LaunchState(sessionEnded = true, nowMs = t0 + 120_000))!!
        assertEquals(LaunchStatus.LOST, s.status)
        assertTrue(missing(s)["wine.log"]!!.isNotEmpty())
    }

    @Test
    fun theCompositorsLogIsSlicedToTheLaunchAndSummarisedPerWindow() {
        val wayland = File(tmp.root, "wayland.log")
        wayland.writeText(listOf(
            compositorLine(t0 - 60_000, "stats", "before the game", zone),
            compositorLine(t0 + 10_000, "stats", "last 10 s: 600 frames on screen (60.0 fps) | 600 GPU frames from games | 0 window redraws | 1 windows open", zone),
            compositorLine(t0 + 10_001, "perf", perfText(copy = 600), zone),
            compositorLine(t0 + 10_002, "pacing", pacingText(600, 16.7), zone),
            "    a continuation line without a stamp",
            compositorLine(t0 + 20_000, "pacing", pacingText(598, 16.8, "on"), zone),
            compositorLine(t0 + 300_000, "stats", "long after the game", zone),
        ).joinToString("\n", postfix = "\n"))
        val app = File(tmp.root, "app.log")
        app.writeText(listOf(logcatLine(t0 - 60_000, "Old: before", zone), logcatLine(t0 + 5_000, "SessionService: during", zone)).joinToString("\n", postfix = "\n"))
        val launch = launcher.start(output = "x\n")
        launcher.exit(launch, code = 0, durationMs = 25_000)
        val s = finish(launch, sources = HostSources(hostSession = "2025-10-09-01-steam", waylandLog = wayland, appLog = app))!!
        val slice = File(s.dir, "wayland.log").readText()
        assertFalse(slice.contains("before the game"))
        assertFalse(slice.contains("long after"))
        assertTrue(slice.contains("a continuation line"))
        assertEquals("${logcatLine(t0 + 5_000, "SessionService: during", zone)}\n", File(s.dir, "android.log").readText())
        val csv = File(s.dir, "performance.csv").readLines()
        assertEquals(PerformanceCsv.COLUMNS.joinToString(","), csv[0])
        assertEquals(3, csv.size)
        val row = csv[1].split(",")
        fun col(name: String) = row[PerformanceCsv.COLUMNS.indexOf(name)]
        assertEquals("10.0", col("seconds_since_launch"))
        assertEquals("60.0", col("onscreen_fps"))
        assertEquals("600", col("frames_committed"))
        assertEquals("16.7", col("commit_p50_ms"))
        assertEquals("600", col("copy_scenes"))
        assertEquals("on", csv[2].split(",")[PerformanceCsv.COLUMNS.indexOf("layer_mode")])
        assertEquals("2025-10-09-01-steam", manifest(s).getJSONObject("host_session").getString("id"))
    }

    @Test
    fun lowDiskKeepsTheRecordsAndSaysWhatWasSkipped() {
        free = 1024
        val wayland = File(tmp.root, "wayland.log").apply { writeText(compositorLine(t0 + 1000, "stats", "x", zone) + "\n") }
        val launch = launcher.start(output = "x\n")
        launcher.exit(launch, code = 0)
        val s = finish(launch, sources = HostSources(waylandLog = wayland))!!
        assertTrue(s.warnings.any { it.contains("low disk space") })
        assertEquals("skipped: low disk space", missing(s)["wayland.log"])
        assertTrue(File(s.dir, "process_exit.json").isFile)
        assertTrue(File(s.dir, "wine.log").isFile)
    }

    @Test
    fun aFileThatCannotBeWrittenIsAWarningNotALostLaunch() {
        val launch = launcher.start(output = "x\n")
        launcher.exit(launch, code = 0)
        // session.log's temporary file is a directory: writing it fails.
        File(store.partialDir("steam-42", launch.id), "session.log.tmp").mkdirs()
        val s = finish(launch)!!
        assertTrue(s.warnings.any { it.startsWith("session.log could not be written") })
        assertEquals("not collected", missing(s)["session.log"])
        assertTrue(File(s.dir, "manifest.json").isFile)
    }

    @Test
    fun aCappedOutputIsMarkedTruncated() {
        val launch = launcher.start(output = "x".repeat(100) + "\n")
        launcher.exit(launch, code = 0, capped = true)
        val s = finish(launch)!!
        val wine = (0 until manifest(s).getJSONArray("files").length()).map { manifest(s).getJSONArray("files").getJSONObject(it) }.first { it.getString("name") == "wine.log" }
        assertTrue(wine.getBoolean("truncated"))
        assertTrue(s.warnings.any { it.contains("wine.log") })
    }

    @Test
    fun aFinalizeCutShortIsDoneAgainFromTheInbox() {
        val launch = launcher.start(output = "kept\n")
        launcher.exit(launch, code = 0)
        // A first pass that moved the output in and died before its manifest.
        val partial = store.partialDir("steam-42", launch.id).apply { mkdirs() }
        File(store.inbox, "${launch.id}.out.log").renameTo(File(partial, "wine.log"))
        val s = finish(launch)!!
        assertEquals("kept\n", File(s.dir, "wine.log").readText())
        assertFalse(store.partialDir("steam-42", launch.id).exists())
        // And done once: a second finalize of the same records finds the folder.
        assertNotNull(store.session("steam-42", launch.id))
    }

    @Test
    fun fexAndCpuSamplesComeInWithMillisecondsWhenTheTimerIsKnown() {
        val launch = launcher.start(output = "x\n", extra = mapOf("cpu_ids" to mapOf("midr_el1" to listOf("0x00000000510f0020"), "error" to null)))
        File(store.inbox, "${launch.id}.fex.csv").writeText(
            "t_s,pid,app_type,fex_version,threads,jit_ticks,signal_ticks,sigbus\n" +
                "10.0,500,arm64ec,FEX-2609,12,19200000,96000,7\n20.0,500,arm64ec,FEX-2609,12,19392000,96000,9\n")
        File(store.inbox, "${launch.id}.cpu.csv").writeText("t_s,cpu0_khz,thermal_max_millic,thermal_max_zone\n10.0,3300000,45000,cpu-0\n")
        launcher.exit(launch, code = 0, sampled = true)
        val s = finalizer.finalize(LaunchRecords(store.inbox, launch.id), LaunchState(sessionEnded = true), HostSources(),
            SessionInfo(device = mapOf("counter_hz" to 19_200_000L)))!!
        val fex = File(s.dir, "fex_stats.csv").readLines()
        assertEquals("t_s,pid,app_type,fex_version,threads,jit_ticks,signal_ticks,sigbus,jit_ms,signal_ms", fex[0])
        assertEquals("10.0,500,arm64ec,FEX-2609,12,19200000,96000,7,1000.000,5.000", fex[1])
        assertEquals("1010.000", fex[2].split(",")[8])
        assertEquals("10.0,3300000,45000,cpu-0", File(s.dir, "cpu.csv").readLines()[1])
        assertFalse(File(store.inbox, "${launch.id}.fex.csv").exists())
        assertFalse(missing(s).containsKey("fex_stats.csv"))
        val manifest = JSONObject(File(s.dir, "manifest.json").readText())
        assertTrue(manifest.toString().contains("every 5 s"))
        // What the guest could read of the CPU's identity: Wine hands it to FEX for its errata.
        assertEquals("0x00000000510f0020", manifest.getJSONObject("launch").getJSONObject("guest_cpu_ids").getJSONArray("midr_el1").getString(0))
    }

    @Test
    fun withoutSamplesTheManifestSaysWhy() {
        val sampledOff = launcher.start(output = "x\n")
        launcher.exit(sampledOff, code = 0, sampled = false)
        assertTrue(missing(finish(sampledOff)!!)["fex_stats.csv"]!!.contains("took no samples"))
        val noFex = launcher.start(output = "x\n", startMs = t0 + 10)
        launcher.exit(noFex, code = 0, sampled = true)
        val s = finish(noFex)!!
        assertTrue(missing(s)["fex_stats.csv"]!!.contains("FEX wrote no statistics"))
        assertTrue(missing(s)["cpu.csv"]!!.contains("did not let"))
        // Without the timer's frequency the ticks stay as they are.
        val ticks = launcher.start(output = "x\n", startMs = t0 + 20)
        File(store.inbox, "${ticks.id}.fex.csv").writeText("t_s,jit_ticks\n10.0,5\n")
        launcher.exit(ticks, code = 0, sampled = true)
        assertEquals("t_s,jit_ticks", File(finish(ticks)!!.dir, "fex_stats.csv").readLines()[0])
    }

    @Test
    fun retentionKeepsTheNewestPerGameAndStaysUnderTheTotal() {
        val limits = DiagnosticsLimits(sessionsPerGame = 3, totalBytes = Long.MAX_VALUE)
        repeat(5) { i ->
            val l = launcher.start(appId = "42", output = "run $i\n", startMs = t0 + i * 1000L)
            launcher.exit(l, code = 0)
            finish(l)
        }
        val other = launcher.start(appId = "77", output = "y".repeat(50_000) + "\n", startMs = t0 + 10_000)
        launcher.exit(other, code = 0)
        finish(other)
        val gone = store.enforce(limits)
        assertEquals(2, gone.size)
        assertEquals(listOf("run 4\n", "run 3\n", "run 2\n"), store.sessions("steam-42").map { File(it.dir, "wine.log").readText() })
        // Over the total: the oldest launches of any game go first.
        val total = store.bytes()
        store.enforce(limits.copy(totalBytes = total - 1))
        assertEquals(listOf("run 4\n", "run 3\n"), store.sessions("steam-42").map { File(it.dir, "wine.log").readText() })
        assertTrue(store.bytes() < total)
        // A game with nothing left disappears from the list.
        store.enforce(limits.copy(totalBytes = 0))
        assertTrue(store.games().isEmpty())
    }

    @Test
    fun deletingALaunchOrAGame() {
        val a = launcher.start(appId = "42", output = "a\n"); launcher.exit(a, code = 0); val sa = finish(a)!!
        val b = launcher.start(appId = "42", output = "b\n", startMs = t0 + 5000); launcher.exit(b, code = 0); finish(b)
        assertTrue(store.delete("steam-42", sa.id))
        assertEquals(1, store.sessions("steam-42").size)
        assertEquals(1, store.deleteGame("steam-42"))
        assertTrue(store.games().isEmpty())
        assertFalse(store.delete("steam-42", "../../etc"))
    }
}
