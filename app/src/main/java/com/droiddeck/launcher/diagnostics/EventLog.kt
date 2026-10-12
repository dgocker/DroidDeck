package com.droiddeck.launcher.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The two clocks an event carries. Swapped for a fixed one in tests. */
interface DiagClock {
    fun wallMs(): Long
    fun monoNs(): Long

    companion object {
        /** Wall time and CLOCK_BOOTTIME, the clock the guest's launch records read too. */
        val SYSTEM = object : DiagClock {
            override fun wallMs() = System.currentTimeMillis()
            override fun monoNs() = android.os.SystemClock.elapsedRealtimeNanos()
        }
    }
}

/**
 * Where diagnostic events are written: posted from any thread without waiting, written by one
 * background thread, never by the poster.
 *
 * [post] is what a session's own threads call, the compositor's and the render path's included:
 * it puts the event in the in-memory ring ([ring], kept whatever the disk does) and offers it to a
 * bounded queue. A full queue drops the event and counts it ([Stats.dropped]); it never blocks and
 * never touches the disk. The writer thread takes events off in batches and appends them to [file]
 * as JSON lines. When the file would pass [maxBytes] it becomes `<file>.1` (the previous `.1` goes)
 * and a new one starts, so the log never holds more than twice [maxBytes]. A write that fails (a
 * full disk, storage gone) loses that batch from the disk only - it is still in the ring - and is
 * counted; the next batch tries again.
 */
class EventLog(
    val file: File,
    @Volatile var level: Severity = Severity.INFO,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    queueCapacity: Int = DEFAULT_QUEUE,
    ringCapacity: Int = DEFAULT_RING,
    private val clock: DiagClock = DiagClock.SYSTEM,
    private val writer: (File, ByteArray) -> Unit = ::append,
) {
    class Stats(
        val posted: Long, val written: Long, val filtered: Long, val dropped: Long,
        val writeErrors: Long, val lastError: String?, val ringOverwritten: Long, val rotations: Long,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "posted" to posted, "written" to written, "filtered_by_level" to filtered, "dropped_queue_full" to dropped,
            "write_errors" to writeErrors, "last_write_error" to lastError, "ring_overwritten" to ringOverwritten,
            "rotations" to rotations,
        )
    }

    val ring = EventRing(ringCapacity)
    private val queue = LinkedBlockingQueue<Any>(queueCapacity)
    private val posted = AtomicLong()
    private val written = AtomicLong()
    private val filtered = AtomicLong()
    private val dropped = AtomicLong()
    private val writeErrors = AtomicLong()
    private val rotations = AtomicLong()
    @Volatile private var lastError: String? = null
    @Volatile private var closed = false
    private var length = -1L
    private val thread = Thread(::drain, "diag-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }

    /** An event stamped with this log's clocks. */
    fun event(source: String, severity: Severity, name: String, fields: Map<String, Any?> = emptyMap()) =
        DiagEvent(clock.wallMs(), clock.monoNs(), source, severity, name, fields)

    fun post(source: String, severity: Severity, name: String, fields: Map<String, Any?> = emptyMap()): Boolean =
        post(event(source, severity, name, fields))

    /** Queues [event] for the disk; false when the level filters it or the queue is full. Never blocks. */
    fun post(event: DiagEvent): Boolean {
        if (!event.severity.within(level)) {
            filtered.incrementAndGet()
            return false
        }
        ring.add(event)
        if (closed || !queue.offer(event)) {
            dropped.incrementAndGet()
            return false
        }
        posted.incrementAndGet()
        return true
    }

    /**
     * Waits up to [timeoutMs] for everything posted before this call to have been written (or to
     * have failed). Never call it from a thread that must not wait; teardown and tests use it.
     */
    fun flush(timeoutMs: Long): Boolean {
        val done = CountDownLatch(1)
        if (!queue.offer(done, timeoutMs, TimeUnit.MILLISECONDS)) return false
        return done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Stops taking events; what is queued is still written. */
    fun close(timeoutMs: Long = 2000) {
        flush(timeoutMs)
        closed = true
        thread.interrupt()
    }

    fun stats(): Stats = Stats(
        posted.get(), written.get(), filtered.get(), dropped.get(), writeErrors.get(), lastError, ring.overwritten, rotations.get(),
    )

    /** The events on disk, older file first, that [keep] accepts; a line that does not parse is skipped. */
    fun read(keep: (DiagEvent) -> Boolean = { true }): List<DiagEvent> =
        listOf(File(file.path + ".1"), file).flatMap { f ->
            try {
                if (!f.isFile) emptyList() else f.useLines { lines -> lines.mapNotNull { DiagEvent.parse(it) }.filter(keep).toList() }
            } catch (e: IOException) {
                emptyList()
            }
        }

    private fun drain() {
        val batch = ArrayList<Any>(256)
        while (true) {
            batch.clear()
            val first = try {
                queue.poll(1, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                if (closed && queue.isEmpty()) return else continue
            }
            if (first == null) {
                if (closed) return else continue
            }
            batch.add(first)
            queue.drainTo(batch, 255)
            val text = StringBuilder()
            var events = 0
            val latches = ArrayList<CountDownLatch>()
            for (item in batch) {
                when (item) {
                    is DiagEvent -> {
                        text.append(try { item.toLine() } catch (e: Exception) { "{\"event\":\"collector.unencodable\"}" }).append('\n')
                        events++
                    }
                    is CountDownLatch -> latches += item
                }
            }
            if (events > 0) write(text.toString().toByteArray(Charsets.UTF_8), events)
            latches.forEach { it.countDown() }
        }
    }

    private fun write(bytes: ByteArray, events: Int) {
        try {
            if (length < 0) length = if (file.isFile) file.length() else 0L
            if (length > 0 && length + bytes.size > maxBytes) {
                val old = File(file.path + ".1")
                old.delete()
                if (!file.renameTo(old)) file.delete()
                length = 0
                rotations.incrementAndGet()
            }
            writer(file, bytes)
            length += bytes.size
            written.addAndGet(events.toLong())
        } catch (e: Exception) {
            writeErrors.incrementAndGet()
            lastError = e.message ?: e.javaClass.simpleName
            length = -1
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 4L * 1024 * 1024
        const val DEFAULT_QUEUE = 4096
        const val DEFAULT_RING = 512

        fun append(file: File, bytes: ByteArray) {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { it.write(bytes) }
        }
    }
}
