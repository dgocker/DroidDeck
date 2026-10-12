package com.droiddeck.launcher.diagnostics

import com.droiddeck.launcher.core.LogRedactor
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The last pass over every line that leaves the device in a diagnostics export: the share redactor
 * every log zip gets ([LogRedactor.redactForShare] - Steam tokens and accounts, GUIDs, URLs' queries,
 * the device's own addresses, LAN addresses), then the values of anything named like a credential
 * the way environments and launch options name them: `AUTH_PASSWORD=...`, `"GITHUB_TOKEN": "..."`,
 * `SESSION_KEY: ...`. A JSON value keeps its quotes, so a redacted manifest still parses.
 */
object DiagnosticRedactor {
    const val MARK = "<redacted>"
    private val NAMED_SECRET = Regex(
        """(?i)(?<![A-Za-z0-9])([A-Za-z0-9_\-]*(?:password|passwd|token|secret|api[_-]?key|private[_-]?key|auth|ticket|cookie|credential|session[_-]?key|epicovt)[A-Za-z0-9_\-]*)("?\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s"',;&<>}\]]+)""",
    )

    fun line(text: String): String {
        val shared = LogRedactor.redactForShare(text)
        return try {
            NAMED_SECRET.replace(shared) { m ->
                val value = m.groupValues[3]
                val blank = when {
                    value.startsWith("<redacted") || value.trim('"', '\'').startsWith("<redacted") -> value
                    value.startsWith("\"") -> "\"$MARK\""
                    value.startsWith("'") -> "'$MARK'"
                    // A JSON key named like a secret whose value is a number, a boolean or null keeps it.
                    m.groupValues[2].startsWith("\"") && (value == "null" || value == "true" || value == "false" || value.toDoubleOrNull() != null) -> value
                    else -> MARK
                }
                m.groupValues[1] + m.groupValues[2] + blank
            }
        } catch (t: Throwable) {
            "<redaction failed; line withheld>"
        }
    }
}

class ExportResult(
    val zip: File?,
    val error: String?,
    val entries: Int = 0,
    val skipped: List<String> = emptyList(),
)

/**
 * One zip of one or more finished launches, laid out as the store is:
 * `diagnostics/<game>/<launch>/manifest.json`, `.../session.log`, ... and `EXPORT.txt` at the root
 * (when, how it was redacted, anything that could not be read). Every text file goes through
 * [DiagnosticRedactor] line by line; nothing else is in a launch folder.
 *
 * Built as `<name>.zip.part` and renamed once it has been read back whole (every entry inflated,
 * its CRC checked, every manifest parsed), so a half-written or corrupt zip is never handed on. It
 * starts only when the disk has room for it (the launches' size plus [marginBytes]); a write that
 * fails anyway (the disk filled up meanwhile) removes the partial zip and says so.
 */
object DiagnosticExport {
    private val TEXT = setOf("log", "txt", "json", "jsonl", "csv")

    fun export(
        sessions: List<SessionSummary>,
        outDir: File,
        name: String,
        freeBytes: (File) -> Long = { it.usableSpace },
        marginBytes: Long = 16L * 1024 * 1024,
        redact: (String) -> String = DiagnosticRedactor::line,
        now: Long = System.currentTimeMillis(),
    ): ExportResult {
        if (sessions.isEmpty()) return ExportResult(null, "nothing to export")
        if (!outDir.isDirectory && !outDir.mkdirs()) return ExportResult(null, "could not create ${outDir.name}")
        val need = sessions.sumOf { it.bytes } + marginBytes
        val free = freeBytes(outDir)
        if (free < need) return ExportResult(null, "not enough free space: about ${need / (1024 * 1024) + 1} MB needed, ${free / (1024 * 1024)} MB free")
        val zip = File(outDir, "$name.zip")
        val part = File(outDir, "$name.zip.part")
        val skipped = ArrayList<String>()
        val written = ArrayList<String>()
        try {
            ZipOutputStream(part.outputStream().buffered(64 * 1024)).use { z ->
                for (s in sessions) {
                    val base = "diagnostics/${DiagnosticsStore.safe(s.gameKey)}/${DiagnosticsStore.safe(s.id)}/"
                    s.dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(s.dir).path }.forEach { f ->
                        val rel = f.relativeTo(s.dir).path.replace(File.separatorChar, '/')
                        if (f.extension.lowercase() !in TEXT) {
                            skipped += "$base$rel: not a text file, left out"
                            return@forEach
                        }
                        val entry = base + rel
                        val reader = try {
                            f.bufferedReader()
                        } catch (e: IOException) {
                            skipped += "$entry: could not be opened (${e.message}), left out"
                            return@forEach
                        }
                        // Line by line into the entry: a log of any size costs one line of memory. A read
                        // that fails part way ends the entry with a line saying so.
                        z.putNextEntry(ZipEntry(entry).apply { time = f.lastModified() })
                        val out = z.bufferedWriter(Charsets.UTF_8)
                        try {
                            reader.use { r ->
                                while (true) {
                                    val line = try { r.readLine() } catch (e: IOException) {
                                        out.write("[export: the rest of this file could not be read: ${e.message}]\n")
                                        skipped += "$entry: cut short by a read error (${e.message})"
                                        null
                                    } ?: break
                                    out.write(redact(line))
                                    out.write("\n")
                                }
                            }
                        } finally {
                            out.flush()
                        }
                        z.closeEntry()
                        written += entry
                    }
                }
                val readme = StringBuilder()
                    .append("DroidDeck game diagnostics export, ").append(DiagEvent.iso(now)).append("\n\n")
                    .append(sessions.size).append(" launch(es); layout diagnostics/<game>/<launch>/, each described by its manifest.json.\n")
                    .append("Every text line went through the log redactor (rules ").append(LogRedactor.RULES_VERSION)
                    .append(") and the credential rules of DiagnosticRedactor before it was written here.\n")
                    .append("Nothing was uploaded; this file exists because it was exported by hand.\n")
                if (skipped.isNotEmpty()) readme.append("\nLeft out:\n").append(skipped.joinToString("\n", postfix = "\n"))
                z.putNextEntry(ZipEntry("EXPORT.txt"))
                z.write(readme.toString().toByteArray(Charsets.UTF_8))
                z.closeEntry()
                written += "EXPORT.txt"
            }
            val problem = verify(part, written)
            if (problem != null) {
                part.delete()
                return ExportResult(null, "the zip did not verify: $problem")
            }
            zip.delete()
            if (!part.renameTo(zip)) {
                part.delete()
                return ExportResult(null, "could not rename the finished zip")
            }
            return ExportResult(zip, null, written.size, skipped)
        } catch (e: IOException) {
            part.delete()
            return ExportResult(null, if (isNoSpace(e)) "the disk filled up while the zip was written" else "the zip could not be written: ${e.message}")
        }
    }

    /** Null when [zip] holds exactly [expected], every entry inflates with a matching CRC and every manifest parses. */
    fun verify(zip: File, expected: List<String>): String? = try {
        val seen = ArrayList<String>()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val e = z.nextEntry ?: break
                val bytes = java.io.ByteArrayOutputStream()
                while (true) {
                    val n = z.read(buf)
                    if (n < 0) break
                    if (e.name.endsWith("/manifest.json")) bytes.write(buf, 0, n)
                }
                if (e.name.endsWith("/manifest.json")) JSONObject(bytes.toString("UTF-8"))
                seen += e.name
            }
        }
        if (seen != expected) "entries differ (${seen.size} read, ${expected.size} written)" else null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    private fun isNoSpace(e: IOException): Boolean = e.message?.contains("ENOSPC") == true || e.message?.contains("No space left", ignoreCase = true) == true
}
