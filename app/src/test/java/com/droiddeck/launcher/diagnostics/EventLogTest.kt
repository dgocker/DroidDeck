package com.droiddeck.launcher.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EventLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = FakeClock()

    private fun log(
        maxBytes: Long = EventLog.DEFAULT_MAX_BYTES, queue: Int = 1024, ring: Int = 64,
        writer: (java.io.File, ByteArray) -> Unit = EventLog::append,
    ) = EventLog(java.io.File(tmp.root, "events.jsonl"), Severity.INFO, maxBytes, queue, ring, clock, writer)

    @Test
    fun theRingKeepsTheNewestEventsInOrder() {
        val ring = EventRing(4)
        for (i in 1..10) ring.add(DiagEvent(i.toLong(), i.toLong(), "test", Severity.INFO, "e$i"))
        assertEquals(listOf("e7", "e8", "e9", "e10"), ring.snapshot().map { it.event })
        assertEquals(6L, ring.overwritten)
        assertEquals(4, ring.size())
    }

    @Test
    fun eventsAreWrittenInOrderInTheUnifiedFormat() {
        val log = log()
        for (i in 1..100) { clock.wall += 1; clock.mono += 1000; log.post("app", Severity.INFO, "tick", mapOf("i" to i, "nested" to mapOf("a" to listOf(1, 2)))) }
        assertTrue(log.flush(5000))
        val back = log.read()
        assertEquals((1..100).toList(), back.map { (it.fields["i"] as Number).toInt() })
        val first = org.json.JSONObject(java.io.File(tmp.root, "events.jsonl").readLines().first())
        assertEquals(listOf("event", "fields", "mono_ns", "severity", "source", "ts", "wall_ms"), first.keys().asSequence().sorted().toList())
        assertEquals("app", first.getString("source"))
        assertEquals("info", first.getString("severity"))
        assertTrue(first.getString("ts").endsWith("Z"))
        assertEquals(100L, log.stats().written)
        log.close()
    }

    @Test
    fun theLevelFiltersBeforeAnythingIsQueued() {
        val log = log()
        log.level = Severity.WARN
        assertFalse(log.post("app", Severity.INFO, "chatty"))
        assertFalse(log.post("app", Severity.DEBUG, "chattier"))
        assertTrue(log.post("app", Severity.ERROR, "bad"))
        log.flush(5000)
        assertEquals(listOf("bad"), log.read().map { it.event })
        assertEquals(2L, log.stats().filtered)
        assertEquals(listOf("bad"), log.ring.snapshot().map { it.event })
    }

    @Test
    fun postingNeverWaitsForTheDiskAndNeverWritesOnTheCallersThread() {
        val gate = CountDownLatch(1)
        val writers = Collections.synchronizedSet(HashSet<String>())
        val log = log(queue = 64) { file, bytes ->
            writers += Thread.currentThread().name
            gate.await(10, TimeUnit.SECONDS)   // a disk that has stopped answering
            EventLog.append(file, bytes)
        }
        val started = System.nanoTime()
        var accepted = 0
        repeat(10_000) { if (log.post("compositor", Severity.INFO, "frame", mapOf("n" to it))) accepted++ }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("10 000 posts took $ms ms with the disk stuck", ms < 2000)
        val stats = log.stats()
        assertTrue("some were dropped: ${stats.dropped}", stats.dropped > 0)
        assertEquals(10_000L, stats.dropped + accepted)
        // The newest are in memory whatever the disk did.
        assertEquals(9_999, (log.ring.snapshot().last().fields["n"] as Number).toInt())
        gate.countDown()
        assertTrue(log.flush(10_000))
        assertEquals(setOf("diag-log"), writers.toSet())
    }

    @Test
    fun aFailingDiskLosesOnlyTheDiskCopyAndRecovers() {
        var full = true
        val log = log { file, bytes -> if (full) throw IOException("write failed: ENOSPC (No space left on device)") else EventLog.append(file, bytes) }
        log.post("app", Severity.INFO, "while-full")
        assertTrue(log.flush(5000))
        assertEquals(1L, log.stats().writeErrors)
        assertTrue(log.stats().lastError!!.contains("ENOSPC"))
        assertEquals(listOf("while-full"), log.ring.snapshot().map { it.event })
        full = false
        log.post("app", Severity.INFO, "after")
        assertTrue(log.flush(5000))
        assertEquals(listOf("after"), log.read().map { it.event })
    }

    @Test
    fun theFileRotatesAndStaysWithinTwiceItsLimit() {
        val log = log(maxBytes = 2000)
        for (i in 1..200) { log.post("app", Severity.INFO, "rotating", mapOf("i" to i)); if (i % 10 == 0) log.flush(5000) }
        assertTrue(log.flush(5000))
        val current = java.io.File(tmp.root, "events.jsonl")
        val older = java.io.File(tmp.root, "events.jsonl.1")
        assertTrue(older.isFile)
        assertTrue(current.length() + older.length() <= 4000)
        assertTrue(log.stats().rotations > 0)
        assertEquals(200, (log.read().last().fields["i"] as Number).toInt())
    }

    @Test
    fun aTornLineIsSkippedOnRead() {
        val f = java.io.File(tmp.root, "events.jsonl")
        f.writeText(DiagEvent(1, 2, "app", Severity.INFO, "whole").toLine() + "\n{\"wall_ms\": 3, \"sour")
        assertEquals(listOf("whole"), log().read().map { it.event })
    }
}
