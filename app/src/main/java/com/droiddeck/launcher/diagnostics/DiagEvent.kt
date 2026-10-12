package com.droiddeck.launcher.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** How much a diagnostic event matters; a log at [INFO] keeps errors, warnings and info. */
enum class Severity(val label: String) {
    ERROR("error"), WARN("warn"), INFO("info"), DEBUG("debug");

    /** Kept by a log whose level is [level]: as important as it, or more. */
    fun within(level: Severity): Boolean = ordinal <= level.ordinal

    companion object {
        fun of(label: String?): Severity = entries.firstOrNull { it.label == label } ?: INFO
    }
}

/**
 * One thing that happened, in the one format every source of game diagnostics is written in: wall
 * time (UTC), the boot clock (CLOCK_BOOTTIME - SystemClock.elapsedRealtimeNanos() in the app, the
 * same clock the guest's launch records read, so the two line up and survive a clock change), where
 * it came from, how much it matters, what it was, and its fields. One JSON object per line on disk:
 *
 *     {"ts":"2026-10-12T10:15:30.123Z","wall_ms":...,"mono_ns":...,"source":"guest","severity":"info","event":"game.started","fields":{...}}
 */
class DiagEvent(
    val wallMs: Long,
    val monoNs: Long,
    val source: String,
    val severity: Severity,
    val event: String,
    val fields: Map<String, Any?> = emptyMap(),
) {
    fun toJson(): JSONObject {
        val f = JSONObject()
        fields.forEach { (k, v) -> f.put(k, jsonValue(v)) }
        return JSONObject()
            .put("ts", iso(wallMs))
            .put("wall_ms", wallMs)
            .put("mono_ns", monoNs)
            .put("source", source)
            .put("severity", severity.label)
            .put("event", event)
            .put("fields", f)
    }

    fun toLine(): String = toJson().toString()

    override fun toString(): String = "$source/${severity.label} $event $fields"

    companion object {
        private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun iso(wallMs: Long): String = ISO.format(Instant.ofEpochMilli(wallMs))

        /** A line written by [toLine], or null for anything else (a torn last line, another format). */
        fun parse(line: String): DiagEvent? = try {
            val o = JSONObject(line)
            val fields = LinkedHashMap<String, Any?>()
            o.optJSONObject("fields")?.let { f -> f.keys().forEach { k -> fields[k] = f.opt(k).takeIf { it != JSONObject.NULL } } }
            DiagEvent(
                o.getLong("wall_ms"), o.optLong("mono_ns", 0L), o.getString("source"),
                Severity.of(o.optString("severity")), o.getString("event"), fields,
            )
        } catch (e: Exception) {
            null
        }

        internal fun jsonValue(v: Any?): Any = when (v) {
            null -> JSONObject.NULL
            is Boolean, is Int, is Long, is Double, is String -> v
            is Float -> v.toDouble()
            is Number -> v.toLong()
            is Map<*, *> -> JSONObject().apply { v.forEach { (k, x) -> put(k.toString(), jsonValue(x)) } }
            is Iterable<*> -> JSONArray().apply { v.forEach { put(jsonValue(it)) } }
            is Array<*> -> JSONArray().apply { v.forEach { put(jsonValue(it)) } }
            is JSONObject, is JSONArray -> v
            else -> v.toString()
        }
    }
}
