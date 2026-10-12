package com.droiddeck.launcher.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ExitClassifierTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun exitOf(build: FakeLauncher.(FakeLauncher.Launch) -> Unit): LaunchRecords.Exit {
        val launcher = FakeLauncher(tmp.newFolder())
        val launch = launcher.start()
        launcher.build(launch)
        return LaunchRecords(launcher.inbox, launch.id).exit()!!
    }

    private fun verdict(exit: LaunchRecords.Exit?, evidence: String? = null, alive: Boolean = false, ended: Boolean = false,
                        restarted: Boolean = false, rebooted: Boolean = false) =
        ExitClassifier.classify(ExitFacts(exit, evidence, alive, ended, restarted, rebooted))

    @Test
    fun codeZeroIsANormalExit() {
        val v = verdict(exitOf { exit(it, code = 0) })
        assertEquals(LaunchStatus.EXITED, v.status)
        assertEquals(Certainty.CONFIRMED, v.certainty)
        assertTrue(v.evidence.contains("wait status: exit code 0"))
    }

    @Test
    fun aNonzeroCodeIsAnErrorExitNotACrash() {
        val v = verdict(exitOf { exit(it, code = 3) })
        assertEquals(LaunchStatus.EXITED_WITH_ERROR, v.status)
        assertEquals(Certainty.AMBIGUOUS, v.certainty)
        assertTrue(v.hints.any { it.contains("not proof of a crash") })
    }

    @Test
    fun aNonzeroCodeWithACrashReportIsACrashOnThatEvidence() {
        val v = verdict(exitOf { exit(it, code = 5) }, evidence = "wine: Unhandled page fault on read access to 0000000000000000 at address 0000000140001000")
        assertEquals(LaunchStatus.CRASHED, v.status)
        assertEquals(Certainty.EVIDENCE, v.certainty)
        assertTrue(v.evidence.any { it.startsWith("output: wine: Unhandled page fault") })
    }

    @Test
    fun aCrashReportBesideCodeZeroIsOnlyAHint() {
        val v = verdict(exitOf { exit(it, code = 0) }, evidence = "wine: Unhandled exception 0xc0000005")
        assertEquals(LaunchStatus.EXITED, v.status)
        assertTrue(v.hints.single().contains("crash report"))
    }

    @Test
    fun aCrashSignalIsACrash() {
        val v = verdict(exitOf { exit(it, code = null, signal = 11, signalName = "SIGSEGV", coreDumped = true) })
        assertEquals(LaunchStatus.CRASHED, v.status)
        assertEquals(Certainty.CONFIRMED, v.certainty)
        assertTrue(v.evidence.contains("wait status: killed by SIGSEGV (core dumped)"))
    }

    @Test
    fun aForcedKillIsAmbiguousAboutWhoSentIt() {
        val v = verdict(exitOf { exit(it, code = null, signal = 9, signalName = "SIGKILL") })
        assertEquals(LaunchStatus.KILLED, v.status)
        assertEquals(Certainty.AMBIGUOUS, v.certainty)
        assertTrue(v.hints.single().contains("cannot tell which"))
    }

    @Test
    fun aStopPassedOnByTheLauncherIsAStop() {
        val bySignal = verdict(exitOf { exit(it, code = null, signal = 15, signalName = "SIGTERM", forwarded = listOf("SIGTERM")) })
        assertEquals(LaunchStatus.STOPPED, bySignal.status)
        assertEquals(Certainty.CONFIRMED, bySignal.certainty)
        val byCode = verdict(exitOf { exit(it, code = 1, forwarded = listOf("SIGTERM")) })
        assertEquals(LaunchStatus.STOPPED, byCode.status)
        // A SIGTERM nobody passed through the launcher is not a known stop.
        assertEquals(LaunchStatus.KILLED, verdict(exitOf { exit(it, code = null, signal = 15, signalName = "SIGTERM") }).status)
    }

    @Test
    fun aCommandThatCouldNotStartIsALaunchFailure() {
        val v = verdict(exitOf { exit(it, code = null, launchFailed = true) })
        assertEquals(LaunchStatus.LAUNCH_FAILED, v.status)
        assertTrue(v.evidence.single().contains("No such file"))
    }

    @Test
    fun withoutAnExitRecordTheVerdictSaysWhatHappenedAround() {
        assertEquals(LaunchStatus.RUNNING, verdict(null, alive = true).status)
        assertEquals(LaunchStatus.LOST, verdict(null, ended = true).status)
        val app = verdict(null, ended = true, restarted = true)
        assertEquals(LaunchStatus.INTERRUPTED, app.status)
        assertEquals(Certainty.INFERRED, app.certainty)
        assertTrue(verdict(null, restarted = true, rebooted = true).summary.contains("device restarted"))
        assertEquals(LaunchStatus.UNKNOWN, verdict(null).status)
    }

    @Test
    fun highCodesGetAHintNotADiagnosis() {
        val v = verdict(exitOf { exit(it, code = 139) })
        assertEquals(LaunchStatus.EXITED_WITH_ERROR, v.status)
        assertTrue(v.hints.any { it.contains("signal 11") && it.contains("not proof") })
    }

    @Test
    fun crashReportsAreRecognisedAndWarningsAreNot() {
        assertNull(ExitClassifier.crashEvidence(sequenceOf("0024:err:module:import_dll Library X not found", "fixme:ntdll:NtQuerySystemInformation", "warn:  DXVK: something")))
        for (line in listOf(
            "wine: Unhandled page fault on read access to 0000000000000000",
            "Unhandled exception: page fault on read access to 0x00000000 in 64-bit code",
            "0118:err:seh:NtRaiseException Unhandled exception code c0000409 flags 1 addr 0x1",
            "droiddeck-fex: SIGILL at 0x7f001234 (si_code 1)",
            "*** stack smashing detected ***: terminated",
            "terminate called after throwing an instance of 'std::runtime_error'",
        )) assertEquals(line, ExitClassifier.crashEvidence(sequenceOf("noise", line)))
    }
}
