package com.droiddeck.launcher.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.time.ZoneId
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DiagnosticExportTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var store: DiagnosticsStore
    private lateinit var launcher: FakeLauncher
    private lateinit var out: File

    @Before
    fun setUp() {
        store = DiagnosticsStore(File(tmp.root, "diagnostics")).also { it.ensure() }
        launcher = FakeLauncher(store.inbox)
        out = File(tmp.root, "share")
    }

    private fun session(appId: String, output: String, startMs: Long = 1_760_000_000_000L): SessionSummary {
        val l = launcher.start(appId = appId, output = output, startMs = startMs)
        launcher.exit(l, code = 0)
        return SessionFinalizer(store, DiagnosticsLimits(), ZoneId.of("UTC")) { Long.MAX_VALUE }
            .finalize(LaunchRecords(store.inbox, l.id), LaunchState(sessionEnded = true), HostSources(), SessionInfo())!!
    }

    @Test
    fun theZipHoldsEachLaunchUnderItsGameAndVerifies() {
        val a = session("42", "line a\n")
        val b = session("77", "line b\n", 1_760_000_100_000L)
        val r = DiagnosticExport.export(listOf(a, b), out, "DroidDeck-diagnostics-test")
        assertNull(r.error)
        val zip = r.zip!!
        assertFalse(File(out, "DroidDeck-diagnostics-test.zip.part").exists())
        ZipFile(zip).use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            assertTrue(names.contains("diagnostics/steam-42/${a.id}/manifest.json"))
            assertTrue(names.contains("diagnostics/steam-77/${b.id}/wine.log"))
            assertTrue(names.contains("EXPORT.txt"))
            assertEquals(a.dir.walkTopDown().count { it.isFile } + b.dir.walkTopDown().count { it.isFile } + 1, names.size)
            // The manifest still parses after the redactor went over it.
            val m = JSONObject(z.getInputStream(z.getEntry("diagnostics/steam-42/${a.id}/manifest.json")).bufferedReader().readText())
            assertEquals("Test Game", m.getJSONObject("game").getString("name"))
        }
        assertNull(DiagnosticExport.verify(zip, ZipFile(zip).use { z -> z.entries().asSequence().map { it.name }.toList() }))
    }

    @Test
    fun secretsAreRedactedOnTheWayOut() {
        val s = session("42", listOf(
            "Proton: launching with -AUTH_PASSWORD=hunter2secret -AUTH_TYPE=exchangecode",
            "env GITHUB_TOKEN=ghp_abcdefghijklmnop STEAM_API_KEY: 0123456789abcdef0123456789abcdef",
            "{\"MY_SECRET\": \"value-in-json\", \"session_key\": 42}",
            "GET https://api.example.com/v1/auth?access_token=abc.def.ghi&x=1",
            "[2025-10-09 09:00:00] Using JWT 25484942796017334",
            "steamid 76561198012345678 logged in",
            "plain line stays",
        ).joinToString("\n", postfix = "\n"))
        val zip = DiagnosticExport.export(listOf(s), out, "x").zip!!
        val text = ZipFile(zip).use { z -> z.getInputStream(z.getEntry("diagnostics/steam-42/${s.id}/wine.log")).bufferedReader().readText() }
        for (secret in listOf("hunter2secret", "ghp_abcdefghijklmnop", "0123456789abcdef0123456789abcdef", "value-in-json", "abc.def.ghi", "25484942796017334", "76561198012345678")) {
            assertFalse("$secret leaked:\n$text", text.contains(secret))
        }
        assertTrue(text.contains("plain line stays"))
        assertTrue(text.contains("\"MY_SECRET\": \"<redacted>\""))
        assertTrue(text.contains("\"session_key\": 42"))
        // The stored copy is untouched; only what leaves is redacted.
        assertTrue(File(s.dir, "wine.log").readText().contains("hunter2secret"))
    }

    @Test
    fun anExportNeedsRoomAndLeavesNothingWhenThereIsNone() {
        val s = session("42", "x".repeat(10_000) + "\n")
        val r = DiagnosticExport.export(listOf(s), out, "x", freeBytes = { 1024L })
        assertNull(r.zip)
        assertTrue(r.error!!.contains("not enough free space"))
        assertTrue(out.listFiles().isNullOrEmpty())
    }

    @Test
    fun aDiskThatFillsUpMidwayRemovesThePartialZip() {
        val s = session("42", "x\n".repeat(1000))
        val r = DiagnosticExport.export(listOf(s), out, "x", redact = { if (it == "x") throw java.io.IOException("write failed: ENOSPC (No space left on device)") else it })
        assertNull(r.zip)
        assertTrue(r.error!!.contains("disk filled up"))
        assertTrue(out.listFiles().isNullOrEmpty())
    }

    @Test
    fun aDamagedZipFailsVerification() {
        val s = session("42", "x\n".repeat(5000))
        val zip = DiagnosticExport.export(listOf(s), out, "x").zip!!
        val names = ZipFile(zip).use { z -> z.entries().asSequence().map { it.name }.toList() }
        val bytes = zip.readBytes()
        File(out, "cut.zip").writeBytes(bytes.copyOf(bytes.size / 2))
        assertNotNull(DiagnosticExport.verify(File(out, "cut.zip"), names))
        assertNotNull(DiagnosticExport.verify(zip, names + "missing-entry"))
    }

    @Test
    fun nothingToExportIsSaid() {
        assertEquals("nothing to export", DiagnosticExport.export(emptyList(), out, "x").error)
    }
}
