package com.droiddeck.launcher.diagnostics

/**
 * What to do with one launch in the inbox at one look ([DiagnosticsManager]'s poll). Pure, so every
 * rule is tested; the verdict itself is [ExitClassifier]'s, from what [finish] says about the session.
 */
internal object InboxPolicy {
    /** The compositor's 10 s summary that covers a launch's last seconds is logged by then. */
    const val SETTLE_MS = 11_000L
    /** A launcher seen gone with no exit record gets this long for the record to land (it is written last). */
    const val DEAD_GRACE_MS = 5_000L

    enum class Step { WAIT, FINISH }

    /** [sessionEnded]: what the finalizer is told, for a launch without an exit record. */
    data class Decision(val step: Step, val sessionEnded: Boolean = false)

    /**
     * @param mine the launch was taken on by this app run
     * @param exitEndedMs the exit record's end time, null when there is none
     * @param alive its launcher or its game is still running (pid and start time match)
     * @param deadForMs how long it has been seen not running with no exit record
     * @param ended the host session has ended, or this is the look at app start
     */
    fun decide(mine: Boolean, exitEndedMs: Long?, alive: Boolean, deadForMs: Long, ended: Boolean, now: Long): Decision = when {
        exitEndedMs != null -> if (ended || now >= exitEndedMs + SETTLE_MS) Decision(Step.FINISH, ended) else Decision(Step.WAIT)
        alive -> Decision(Step.WAIT)
        // Taken on by an app run that is gone, and nobody finished it: interrupted.
        !mine -> Decision(Step.FINISH, sessionEnded = true)
        // The session ended under it and its launcher never wrote how it ended: lost.
        ended -> Decision(Step.FINISH, sessionEnded = true)
        // Its launcher is gone while the session goes on: no record, no story - unknown.
        deadForMs >= DEAD_GRACE_MS -> Decision(Step.FINISH, sessionEnded = false)
        else -> Decision(Step.WAIT)
    }
}
