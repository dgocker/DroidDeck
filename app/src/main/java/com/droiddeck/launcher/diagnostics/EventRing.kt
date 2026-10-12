package com.droiddeck.launcher.diagnostics

/**
 * The last [capacity] events, in memory, whatever happened to the disk: what a session that ends
 * badly is read back from ([snapshot]) even when the log on disk is full, failing or behind. Adding
 * is O(1) and never allocates beyond the event itself; the oldest event makes room for the newest.
 */
class EventRing(val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val slots = arrayOfNulls<DiagEvent>(capacity)
    private var next = 0
    private var size = 0

    /** Events pushed out by newer ones since this ring was made. */
    var overwritten: Long = 0
        private set

    @Synchronized
    fun add(event: DiagEvent) {
        if (size == capacity) overwritten++ else size++
        slots[next] = event
        next = (next + 1) % capacity
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): List<DiagEvent> {
        val out = ArrayList<DiagEvent>(size)
        val first = (next - size + capacity) % capacity
        for (i in 0 until size) out.add(slots[(first + i) % capacity]!!)
        return out
    }

    @Synchronized
    fun size(): Int = size

    @Synchronized
    fun clear() {
        slots.fill(null)
        next = 0
        size = 0
    }
}
