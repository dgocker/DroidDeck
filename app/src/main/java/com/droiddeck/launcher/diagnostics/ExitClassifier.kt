package com.droiddeck.launcher.diagnostics

/** How a game launch ended, as far as the records can tell. */
enum class LaunchStatus(val id: String, val problem: Boolean) {
    /** The launch is still going: no exit record, and its process is alive. */
    RUNNING("running", false),
    /** Exit code 0. */
    EXITED("exited", false),
    /** A nonzero exit code and nothing else: games exit with codes of their own, so not a crash on its own. */
    EXITED_WITH_ERROR("exited_with_error", true),
    /** Killed by a crash signal (SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT, SIGTRAP, SIGSYS), or a nonzero exit with a crash report in the game's output. */
    CRASHED("crashed", true),
    /** Killed by another signal (SIGKILL above all) that nobody asked for through the launcher: who sent it is not known. */
    KILLED("killed", true),
    /** The launcher was told to stop (the client's Stop, the session closing) and passed it on. */
    STOPPED("stopped", false),
    /** The game's launch command could not be started at all. */
    LAUNCH_FAILED("launch_failed", true),
    /** The session ended while the game ran and its launcher never wrote how it ended. */
    LOST("lost", true),
    /** DroidDeck or the device went down while the game ran; found at the next app start. */
    INTERRUPTED("interrupted", true),
    UNKNOWN("unknown", true);

    companion object {
        fun of(id: String?): LaunchStatus = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/**
 * What a verdict rests on: [CONFIRMED] is the wait status itself (an exit code, a signal), [EVIDENCE]
 * a crash report in the game's own output, [AMBIGUOUS] a status with more than one plausible cause,
 * [INFERRED] the absence of a record read in the light of what happened to the session or the app.
 */
enum class Certainty(val id: String) { CONFIRMED("confirmed"), EVIDENCE("evidence"), AMBIGUOUS("ambiguous"), INFERRED("inferred") }

class Verdict(
    val status: LaunchStatus,
    val certainty: Certainty,
    val summary: String,
    val evidence: List<String> = emptyList(),
    val hints: List<String> = emptyList(),
)

/** What is known about a launch when it is classified. */
class ExitFacts(
    val exit: LaunchRecords.Exit?,
    /** The first line of a crash report in the game's output ([ExitClassifier.crashEvidence]), if any. */
    val crashEvidence: String? = null,
    /** Its process is still running (pid and start time both match). */
    val processAlive: Boolean = false,
    /** The host session that ran it has ended. */
    val sessionEnded: Boolean = false,
    /** Seen at a later app start: the app run that watched it is gone. */
    val appRestarted: Boolean = false,
    /** The device's boot id differs from the one at the launch's start. */
    val rebooted: Boolean = false,
)

/**
 * Turns a launch's records into a [Verdict]. Pure, so every rule is tested. What it never does:
 * call a launch a crash because a window closed or the game ended early, or say who sent a SIGKILL.
 * A hang is not one of the outcomes: nothing the records hold tells a hung game from a slow one.
 */
object ExitClassifier {
    private val CRASH_SIGNALS = mapOf(4 to "SIGILL", 5 to "SIGTRAP", 6 to "SIGABRT", 7 to "SIGBUS", 8 to "SIGFPE", 11 to "SIGSEGV", 31 to "SIGSYS")
    private val STOP_SIGNALS = setOf(1, 2, 3, 9, 15)

    /**
     * Lines a crashing Wine, glibc or FEX (tools/linuxfs/fex/faultreport.c) writes to stderr. Only
     * reports of a crash, never warnings: `err:` lines alone are everyday Wine output.
     */
    private val CRASH_LINES = listOf(
        Regex("""wine: Unhandled (page fault|exception|illegal instruction|stack overflow|privileged instruction)"""),
        Regex("""Unhandled exception: (page fault|illegal instruction|divide by zero|stack overflow|privileged instruction|assertion failed|unimplemented function)"""),
        Regex("""err:seh:.*[Uu]nhandled exception"""),
        Regex("""droiddeck-fex: SIG(ILL|SEGV|BUS) at 0x"""),
        Regex("""\*\*\* (stack smashing detected|buffer overflow detected) \*\*\*"""),
        Regex("""(double free or corruption|malloc\(\): corrupted|free\(\): invalid pointer)"""),
        Regex("""terminate called after throwing an instance of"""),
        Regex("""wine: Assertion failed"""),
    )

    /** The first crash report in [lines], cut to 300 characters, or null. */
    fun crashEvidence(lines: Sequence<String>): String? =
        lines.firstOrNull { line -> CRASH_LINES.any { it.containsMatchIn(line) } }?.trim()?.take(300)

    fun classify(f: ExitFacts): Verdict {
        val exit = f.exit
        if (exit == null) {
            return when {
                f.processAlive -> Verdict(LaunchStatus.RUNNING, Certainty.CONFIRMED, "The game is still running.")
                f.appRestarted && f.rebooted -> Verdict(
                    LaunchStatus.INTERRUPTED, Certainty.INFERRED,
                    "The device restarted while the game was running; how the game ended was not recorded.",
                    listOf("boot id changed since the launch started"),
                )
                f.appRestarted -> Verdict(
                    LaunchStatus.INTERRUPTED, Certainty.INFERRED,
                    "DroidDeck stopped while the game was running (killed by Android, a crash, or force-stopped); how the game ended was not recorded.",
                    listOf("no exit record, and the app run that watched the launch is gone"),
                )
                f.sessionEnded -> Verdict(
                    LaunchStatus.LOST, Certainty.INFERRED,
                    "The session ended while the game was running; its launcher was stopped before it could record how the game ended.",
                    listOf("no exit record after the session's teardown"),
                )
                else -> Verdict(LaunchStatus.UNKNOWN, Certainty.INFERRED, "No exit record, and the process is not running.")
            }
        }
        val evidence = ArrayList<String>()
        val hints = ArrayList<String>()
        if (exit.launchFailed) {
            evidence += exit.execErrors
            return Verdict(LaunchStatus.LAUNCH_FAILED, Certainty.CONFIRMED, "The game's launch command could not be started.", evidence)
        }
        val forwarded = exit.forwardedSignals
        if (forwarded.isNotEmpty()) evidence += "the launcher was sent ${forwarded.joinToString()} and passed it on"
        val signal = exit.signal
        if (signal != null) {
            val name = exit.signalName ?: CRASH_SIGNALS[signal] ?: "signal $signal"
            evidence += "wait status: killed by $name" + if (exit.coreDumped) " (core dumped)" else ""
            f.crashEvidence?.let { evidence += "output: $it" }
            if (signal in CRASH_SIGNALS) {
                return Verdict(LaunchStatus.CRASHED, Certainty.CONFIRMED, "The game was killed by $name.", evidence, hints)
            }
            if (forwarded.isNotEmpty() && signal in STOP_SIGNALS) {
                return Verdict(LaunchStatus.STOPPED, Certainty.CONFIRMED, "The game was stopped from outside ($name).", evidence, hints)
            }
            if (signal == 9) hints += "SIGKILL comes from Android's low-memory killer, the Steam client's reaper or a forced stop; the record cannot tell which."
            return Verdict(LaunchStatus.KILLED, Certainty.AMBIGUOUS, "The game was killed by $name; who sent it is not recorded.", evidence, hints)
        }
        val code = exit.exitCode
        if (exit.exited && code != null) {
            evidence += "wait status: exit code $code"
            if (code == 0) {
                f.crashEvidence?.let { hints += "The output has a crash report, but the game exited with code 0: $it" }
                return Verdict(LaunchStatus.EXITED, Certainty.CONFIRMED, "The game exited normally (code 0).", evidence, hints)
            }
            if (code > 128) hints += "Codes above 128 often mean a process inside the game was killed by signal ${code - 128} and its parent passed the status on; this is not proof."
            if (forwarded.isNotEmpty()) {
                return Verdict(LaunchStatus.STOPPED, Certainty.CONFIRMED, "The game was stopped from outside and exited with code $code.", evidence, hints)
            }
            f.crashEvidence?.let {
                evidence += "output: $it"
                return Verdict(LaunchStatus.CRASHED, Certainty.EVIDENCE, "The game exited with code $code after a crash report in its output.", evidence, hints)
            }
            hints += "Windows games exit with codes of their own; a nonzero code is not proof of a crash."
            return Verdict(LaunchStatus.EXITED_WITH_ERROR, Certainty.AMBIGUOUS, "The game exited with code $code.", evidence, hints)
        }
        return Verdict(LaunchStatus.UNKNOWN, Certainty.AMBIGUOUS, "The exit record holds no exit code or signal.", evidence, hints)
    }
}
