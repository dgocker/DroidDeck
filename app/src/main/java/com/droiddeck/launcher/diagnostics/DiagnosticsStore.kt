package com.droiddeck.launcher.diagnostics

import org.json.JSONObject
import java.io.File

/** Sizes and counts that bound what game diagnostics keep. */
data class DiagnosticsLimits(
    /** Launches kept per game; the oldest go first. */
    val sessionsPerGame: Int = 20,
    /** Everything under the store, inbox and event log included. */
    val totalBytes: Long = 512L * 1024 * 1024,
    /** Each slice of a session-long log (wayland.log, android.log). */
    val sliceBytes: Long = 8L * 1024 * 1024,
    /** Each extract of the game's output (fex.log, vulkan.log) and session.log. */
    val extractBytes: Long = 2L * 1024 * 1024,
    /** Free space a finalize leaves alone; below it, only the small records are written. */
    val minFreeBytes: Long = 64L * 1024 * 1024,
    /** The guest's cap on its copy of the game's stderr (BL_DIAG_OUTPUT_CAP). */
    val outputBytes: Long = 32L * 1024 * 1024,
)

/** One finished launch as its manifest describes it. */
class SessionSummary(
    val gameKey: String,
    val id: String,
    val gameName: String,
    val startedMs: Long,
    val endedMs: Long?,
    val durationMs: Long?,
    val status: LaunchStatus,
    val certainty: String,
    val summary: String,
    val evidence: List<String>,
    val hints: List<String>,
    val warnings: List<String>,
    val missing: List<Pair<String, String>>,
    val files: List<Pair<String, Long>>,
    val bytes: Long,
    val dir: File,
) {
    /** Something a reader should know is not there or not whole. */
    val incomplete: Boolean get() = warnings.isNotEmpty() || status in setOf(LaunchStatus.LOST, LaunchStatus.INTERRUPTED, LaunchStatus.UNKNOWN)
}

class GameSummary(val key: String, val name: String, val sessions: Int, val lastPlayedMs: Long, val lastStatus: LaunchStatus, val problems: Int)

/**
 * Where game diagnostics live, in the app's private files: `diagnostics/<game>/<launch>/` for each
 * finished launch, `diagnostics/inbox/` for the guest's records of launches not yet finished, and
 * `diagnostics/events.jsonl` for the app's own event log. A launch is built in `<launch>.partial`
 * and renamed when its manifest is written, so a half-built one is never listed.
 */
class DiagnosticsStore(val root: File) {
    val inbox: File get() = File(root, INBOX)
    val eventsFile: File get() = File(root, "events.jsonl")

    fun gameDir(key: String) = File(root, safe(key))
    fun sessionDir(key: String, id: String) = File(gameDir(key), safe(id))
    fun partialDir(key: String, id: String) = File(gameDir(key), safe(id) + PARTIAL)

    fun ensure(): Boolean = inbox.isDirectory || inbox.mkdirs()

    /** Every game with at least one finished launch, the most recently played first. */
    fun games(): List<GameSummary> = gameDirs().mapNotNull { dir ->
        val sessions = sessions(dir.name)
        val last = sessions.firstOrNull() ?: return@mapNotNull null
        GameSummary(dir.name, last.gameName, sessions.size, last.startedMs, last.status, sessions.count { it.status.problem })
    }.sortedByDescending { it.lastPlayedMs }

    /** A game's finished launches, newest first. */
    fun sessions(key: String): List<SessionSummary> =
        gameDir(key).listFiles { f -> f.isDirectory && !f.name.endsWith(PARTIAL) }.orEmpty()
            .mapNotNull { read(key, it) }.sortedByDescending { it.startedMs }

    fun session(key: String, id: String): SessionSummary? = sessionDir(key, id).takeIf { it.isDirectory }?.let { read(key, it) }

    fun delete(key: String, id: String): Boolean {
        val dir = sessionDir(key, id)
        if (!dir.isDirectory) return false
        deleteTree(dir)
        gameDir(key).takeIf { it.listFiles().isNullOrEmpty() }?.delete()
        return !dir.exists()
    }

    fun deleteGame(key: String): Int {
        val n = sessions(key).size
        deleteTree(gameDir(key))
        return n
    }

    /** Bytes under the store, inbox and event log included. */
    fun bytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Keeps each game's newest [DiagnosticsLimits.sessionsPerGame] launches, then deletes the
     * oldest launches of any game while the store is over [DiagnosticsLimits.totalBytes]. Only
     * finished launches go; the inbox and the event log (bounded on their own) are never touched.
     * Returns the folders deleted.
     */
    fun enforce(limits: DiagnosticsLimits): List<File> {
        val gone = ArrayList<File>()
        gameDirs().forEach { game ->
            sessions(game.name).drop(limits.sessionsPerGame).forEach { s -> deleteTree(s.dir); gone += s.dir }
        }
        var total = bytes()
        if (total > limits.totalBytes) {
            val all = gameDirs().flatMap { sessions(it.name) }.sortedBy { it.startedMs }
            for (s in all) {
                if (total <= limits.totalBytes) break
                total -= s.bytes
                deleteTree(s.dir)
                gone += s.dir
            }
        }
        gameDirs().filter { it.listFiles().isNullOrEmpty() }.forEach { it.delete() }
        return gone
    }

    private fun gameDirs(): List<File> =
        root.listFiles { f -> f.isDirectory && f.name != INBOX && !f.name.startsWith(".") }.orEmpty().toList()

    private fun read(key: String, dir: File): SessionSummary? {
        val m = try {
            JSONObject(File(dir, MANIFEST).readText())
        } catch (e: Exception) {
            return null
        }
        val game = m.optJSONObject("game") ?: JSONObject()
        val time = m.optJSONObject("time") ?: JSONObject()
        val status = m.optJSONObject("status") ?: JSONObject()
        fun strings(a: org.json.JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty()
        val files = m.optJSONArray("files")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("name") to it.optLong("bytes") } }.orEmpty()
        val missing = m.optJSONArray("missing")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("name") to it.optString("reason") } }.orEmpty()
        return SessionSummary(
            gameKey = key,
            id = dir.name,
            gameName = game.optStringOrNull("name") ?: key,
            startedMs = time.optLong("start_unix_ms", dir.lastModified()),
            endedMs = if (time.isNull("end_unix_ms") || !time.has("end_unix_ms")) null else time.optLong("end_unix_ms"),
            durationMs = if (time.isNull("duration_ms") || !time.has("duration_ms")) null else time.optLong("duration_ms"),
            status = LaunchStatus.of(status.optString("status")),
            certainty = status.optString("certainty"),
            summary = status.optString("summary"),
            evidence = strings(status.optJSONArray("evidence")),
            hints = strings(status.optJSONArray("hints")),
            warnings = strings(m.optJSONArray("warnings")),
            missing = missing,
            files = files,
            bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            dir = dir,
        )
    }

    companion object {
        /** [f] and everything under it; a symbolic link is removed, never followed. */
        fun deleteTree(f: File) {
            if (f.isDirectory && !java.nio.file.Files.isSymbolicLink(f.toPath())) f.listFiles()?.forEach { deleteTree(it) }
            f.delete()
        }

        const val INBOX = "inbox"
        const val PARTIAL = ".partial"
        const val MANIFEST = "manifest.json"
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")

        fun safe(name: String): String = UNSAFE.replace(name, "_").take(96).ifEmpty { "_" }.let { if (it == "." || it == "..") "_" else it }
    }
}
