package com.droiddeck.launcher.diagnostics

import com.droiddeck.launcher.diagnostics.InboxPolicy.Decision
import com.droiddeck.launcher.diagnostics.InboxPolicy.Step.FINISH
import com.droiddeck.launcher.diagnostics.InboxPolicy.Step.WAIT
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxPolicyTest {
    private val now = 1_000_000L

    private fun decide(mine: Boolean = true, exitAt: Long? = null, alive: Boolean = false, deadFor: Long = 0, ended: Boolean = false) =
        InboxPolicy.decide(mine, exitAt, alive, deadFor, ended, now)

    @Test
    fun aRunningLaunchIsLeftAlone() {
        assertEquals(Decision(WAIT), decide(alive = true))
        assertEquals(Decision(WAIT), decide(alive = true, ended = true))
        // An earlier app run's game that is still running is left too.
        assertEquals(Decision(WAIT), decide(mine = false, alive = true, ended = true))
    }

    @Test
    fun anEndedLaunchWaitsForTheCompositorsLastSummaryUnlessTheSessionIsOver() {
        assertEquals(Decision(WAIT), decide(exitAt = now - 5_000))
        assertEquals(Decision(FINISH), decide(exitAt = now - InboxPolicy.SETTLE_MS))
        assertEquals(Decision(FINISH, sessionEnded = true), decide(exitAt = now, ended = true))
    }

    @Test
    fun aLaunchGoneWithoutARecordWhileTheSessionRunsGetsAGrace() {
        assertEquals(Decision(WAIT), decide(deadFor = 0))
        assertEquals(Decision(WAIT), decide(deadFor = InboxPolicy.DEAD_GRACE_MS - 1))
        assertEquals(Decision(FINISH, sessionEnded = false), decide(deadFor = InboxPolicy.DEAD_GRACE_MS))
    }

    @Test
    fun aLaunchTheSessionEndedUnderIsFinishedAsEndedWithIt() {
        assertEquals(Decision(FINISH, sessionEnded = true), decide(ended = true))
    }

    @Test
    fun aLaunchAnEarlierAppRunLeftIsFinishedAtOnce() {
        // Found at the next app start (or by a later session): not this run's, not running, no record.
        assertEquals(Decision(FINISH, sessionEnded = true), decide(mine = false, ended = true))
        assertEquals(Decision(FINISH, sessionEnded = true), decide(mine = false))
        // With a record, it is finished from the record like any other.
        assertEquals(Decision(FINISH, sessionEnded = true), decide(mine = false, exitAt = now - 60_000, ended = true))
    }
}
