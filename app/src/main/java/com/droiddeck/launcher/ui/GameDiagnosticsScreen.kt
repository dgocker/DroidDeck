package com.droiddeck.launcher.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.diagnostics.DiagnosticExport
import com.droiddeck.launcher.diagnostics.DiagnosticsManager
import com.droiddeck.launcher.diagnostics.DiagnosticsPrefs
import com.droiddeck.launcher.diagnostics.DiagnosticsStore
import com.droiddeck.launcher.diagnostics.GameSummary
import com.droiddeck.launcher.diagnostics.LaunchRecords
import com.droiddeck.launcher.diagnostics.LaunchStatus
import com.droiddeck.launcher.diagnostics.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Something read off the disk for a screen: still loading, there, or failed with a reason. */
private sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data class Ready<T>(val value: T) : Loaded<T>
    data class Failed(val message: String) : Loaded<Nothing>
}

@Composable
private fun <T> load(vararg keys: Any?, read: () -> T): Loaded<T> {
    val state by produceState<Loaded<T>>(Loaded.Loading, *keys) {
        value = withContext(Dispatchers.IO) {
            runCatching(read).fold({ Loaded.Ready(it) }, { Loaded.Failed(it.message ?: it.javaClass.simpleName) })
        }
    }
    return state
}

/**
 * Game diagnostics (DiagnosticsActivity): the games with recorded launches, each game's launch
 * history, and one launch's summary - how it ended and on what evidence, its time and duration,
 * what was collected and what was not. Export makes a redacted zip and hands it to Android's share
 * sheet; nothing is ever sent anywhere on its own.
 */
@Composable
fun GameDiagnosticsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val host = rememberMenuHost()
    var gameKey by rememberSaveable { mutableStateOf<String?>(null) }
    var launchId by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmText by remember { mutableStateOf("") }
    val back: () -> Unit = { if (launchId != null) launchId = null else if (gameKey != null) gameKey = null else onClose() }
    BackHandler(enabled = gameKey != null) { back() }
    val store = DiagnosticsManager.store
    val revision = DiagnosticsManager.revision

    fun export(sessions: List<SessionSummary>, name: String) {
        if (exporting) return
        exporting = true
        DiagnosticsManager.background({
            com.droiddeck.launcher.core.LogRedactor.learnFromRuntime(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context))
            DiagnosticExport.export(sessions, File(context.cacheDir, "share"), name)
        }) { result ->
            Handler(Looper.getMainLooper()).post {
                exporting = false
                val zip = result.getOrNull()?.zip
                if (zip != null) runCatching { context.startActivity(com.droiddeck.launcher.session.SessionLogShare.shareIntent(context, zip)) }
                else Toast.makeText(context, context.getString(R.string.diag_export_failed,
                    result.getOrNull()?.error ?: result.exceptionOrNull()?.message ?: "?"), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun ask(text: String, action: () -> Unit) {
        confirmText = text
        confirm = action
    }

    if (store == null) {
        SettingsPage(host, stringResource(R.string.diag_title), onBack = onClose) { Message(stringResource(R.string.diag_error, "not started")) }
        return
    }
    val key = gameKey
    val id = launchId
    when {
        key == null -> GamesPage(context, host, store, revision, onOpen = { gameKey = it }, onBack = onClose)
        id == null -> HistoryPage(context, host, store, key, revision, exporting,
            onOpen = { launchId = it }, onBack = back,
            onExport = { sessions -> export(sessions, "DroidDeck-diagnostics-${DiagnosticsStore.safe(key)}-all") },
            onDelete = { name, count ->
                ask(context.getString(R.string.diag_confirm_delete_game, count, name)) {
                    DiagnosticsManager.background({ store.deleteGame(key) }) { DiagnosticsManager.changed() }
                    gameKey = null
                }
            })
        else -> LaunchPage(context, host, store, key, id, revision, exporting, onBack = back,
            onExport = { s -> export(listOf(s), "DroidDeck-diagnostics-${DiagnosticsStore.safe(key)}-${DiagnosticsStore.safe(id)}") },
            onDelete = {
                ask(context.getString(R.string.diag_confirm_delete_launch)) {
                    DiagnosticsManager.background({ store.delete(key, id) }) { DiagnosticsManager.changed() }
                    launchId = null
                }
            })
    }
    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.diag_confirm_delete_title)) },
            text = { Text(confirmText) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text(stringResource(R.string.diag_delete)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun GamesPage(context: Context, host: MenuHost, store: DiagnosticsStore, revision: Int, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var enabled by remember { mutableStateOf(DiagnosticsPrefs.enabled(context)) }
    var detail by remember { mutableStateOf(DiagnosticsPrefs.detail(context)) }
    // Running launches change without the store changing: looked at again every few seconds.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3_000); tick++ } }
    val games = load(revision) { store.games() }
    val running = load(revision, tick) { DiagnosticsManager.pending() }
    val bytes = load(revision) { store.bytes() }
    SettingsPage(host, stringResource(R.string.diag_title), onBack = onBack, lede = stringResource(R.string.diag_lede)) {
        SettingsGroup(stringResource(R.string.diag_group_settings)) {
            ToggleRow(host, "diag-enabled", stringResource(R.string.diag_record), stringResource(R.string.diag_record_hint), enabled) {
                DiagnosticsPrefs.setEnabled(context, it)
                enabled = it
            }
            ChoiceRow(
                host, "diag-detail", stringResource(R.string.diag_detail), stringResource(R.string.diag_detail_hint),
                DiagnosticsPrefs.Detail.entries.map { it to stringResource(detailLabel(it)) }, detail,
            ) {
                DiagnosticsPrefs.setDetail(context, it)
                detail = it
            }
            SettingsRow(stringResource(R.string.diag_storage), stringResource(
                R.string.diag_storage_hint,
                (bytes as? Loaded.Ready)?.let { Formatter.formatShortFileSize(context, it.value) } ?: "…",
                DiagnosticsManager.limits.sessionsPerGame,
            )) {}
        }
        (running as? Loaded.Ready)?.value?.takeIf { it.isNotEmpty() }?.let { pending ->
            SettingsGroup(stringResource(R.string.diag_group_running)) {
                pending.forEach { start -> RunningRow(context, start) }
            }
        }
        SettingsGroup(stringResource(R.string.diag_group_games)) {
            when (games) {
                is Loaded.Loading -> Message(stringResource(R.string.diag_loading))
                is Loaded.Failed -> Message(stringResource(R.string.diag_error, games.message), error = true)
                is Loaded.Ready -> if (games.value.isEmpty()) Message(stringResource(R.string.diag_empty)) else games.value.forEach { g ->
                    ActionRow(g.name, gameHint(context, g), stringResource(R.string.diag_history), onClick = { onOpen(g.key) })
                }
            }
        }
    }
}

@Composable
private fun RunningRow(context: Context, start: LaunchRecords.Start) {
    SettingsRow(start.name ?: start.gameKey, stringResource(R.string.diag_running_since, time(context, start.startedUnixMs))) {
        Text(stringResource(R.string.diag_status_running), fontSize = 13.sp, color = LocalPalette.current.good)
    }
}

@Composable
private fun HistoryPage(
    context: Context, host: MenuHost, store: DiagnosticsStore, key: String, revision: Int, exporting: Boolean,
    onOpen: (String) -> Unit, onBack: () -> Unit, onExport: (List<SessionSummary>) -> Unit, onDelete: (String, Int) -> Unit,
) {
    val sessions = load(revision, key) { store.sessions(key) }
    val list = (sessions as? Loaded.Ready)?.value.orEmpty()
    val name = list.firstOrNull()?.gameName ?: key
    SettingsPage(host, name, onBack = onBack, eyebrow = stringResource(R.string.diag_title),
        lede = if (list.isNotEmpty()) pluralStringResource(R.plurals.diag_launches, list.size, list.size) else null) {
        when (sessions) {
            is Loaded.Loading -> Message(stringResource(R.string.diag_loading))
            is Loaded.Failed -> Message(stringResource(R.string.diag_error, sessions.message), error = true)
            is Loaded.Ready -> if (list.isEmpty()) Message(stringResource(R.string.diag_empty)) else {
                SettingsGroup(stringResource(R.string.diag_group_history)) {
                    list.forEach { s ->
                        ActionRow(time(context, s.startedMs), launchHint(context, s), stringResource(R.string.diag_details), onClick = { onOpen(s.id) })
                    }
                }
                SettingsGroup(stringResource(R.string.diag_group_actions)) {
                    ActionRow(stringResource(R.string.diag_export_all), stringResource(R.string.diag_export_hint),
                        stringResource(if (exporting) R.string.common_working else R.string.diag_export), onClick = { onExport(list) })
                    ActionRow(stringResource(R.string.diag_delete_all), stringResource(R.string.diag_delete_all_hint),
                        stringResource(R.string.diag_delete), onClick = { onDelete(name, list.size) })
                }
            }
        }
    }
}

@Composable
private fun LaunchPage(
    context: Context, host: MenuHost, store: DiagnosticsStore, key: String, id: String, revision: Int, exporting: Boolean,
    onBack: () -> Unit, onExport: (SessionSummary) -> Unit, onDelete: () -> Unit,
) {
    val loaded = load(revision, key, id) { store.session(key, id) ?: error("gone") }
    val s = (loaded as? Loaded.Ready)?.value
    SettingsPage(host, s?.let { stringResource(statusLabel(it.status)) } ?: stringResource(R.string.diag_title), onBack = onBack,
        eyebrow = s?.gameName, lede = s?.summary) {
        when (loaded) {
            is Loaded.Loading -> Message(stringResource(R.string.diag_loading))
            is Loaded.Failed -> Message(stringResource(R.string.diag_error, loaded.message), error = true)
            is Loaded.Ready -> {
                val launch = loaded.value
                SettingsGroup(stringResource(R.string.diag_group_outcome)) {
                    SettingsRow(stringResource(R.string.diag_status), stringResource(certaintyLabel(launch.certainty))) {
                        Text(stringResource(statusLabel(launch.status)), fontSize = 14.sp, color = statusColor(launch.status))
                    }
                    SettingsRow(stringResource(R.string.diag_started), time(context, launch.startedMs)) {}
                    SettingsRow(stringResource(R.string.diag_ended), launch.endedMs?.let { time(context, it) } ?: stringResource(R.string.diag_not_recorded)) {}
                    SettingsRow(stringResource(R.string.diag_duration), launch.durationMs?.let { DateUtils.formatElapsedTime(it / 1000) } ?: stringResource(R.string.diag_not_recorded)) {}
                }
                if (launch.evidence.isNotEmpty()) SettingsGroup(stringResource(R.string.diag_group_evidence)) {
                    launch.evidence.forEach { Detail(it) }
                }
                if (launch.hints.isNotEmpty()) SettingsGroup(stringResource(R.string.diag_group_hints)) {
                    launch.hints.forEach { Detail(it) }
                }
                if (launch.warnings.isNotEmpty()) SettingsGroup(stringResource(R.string.diag_group_warnings)) {
                    launch.warnings.forEach { Detail(it, AttentionAmber) }
                }
                SettingsGroup(stringResource(R.string.diag_group_files)) {
                    launch.files.forEach { (name, size) -> SettingsRow(name, Formatter.formatShortFileSize(context, size)) {} }
                }
                if (launch.missing.isNotEmpty()) SettingsGroup(stringResource(R.string.diag_group_missing)) {
                    launch.missing.forEach { (name, reason) -> SettingsRow(name, reason) {} }
                }
                SettingsGroup(stringResource(R.string.diag_group_actions)) {
                    ActionRow(stringResource(R.string.diag_export_launch), stringResource(R.string.diag_export_hint),
                        stringResource(if (exporting) R.string.common_working else R.string.diag_export), onClick = { onExport(launch) })
                    ActionRow(stringResource(R.string.diag_delete_launch), null, stringResource(R.string.diag_delete), onClick = onDelete)
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, error: Boolean = false) {
    Text(text, fontSize = 14.sp, color = if (error) LocalPalette.current.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp))
}

@Composable
private fun Detail(text: String, color: Color? = null) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(text, fontSize = 13.sp, color = color ?: MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun statusColor(status: LaunchStatus): Color = when {
    status == LaunchStatus.RUNNING || status == LaunchStatus.EXITED || status == LaunchStatus.STOPPED -> LocalPalette.current.good
    status == LaunchStatus.EXITED_WITH_ERROR || status == LaunchStatus.UNKNOWN || status == LaunchStatus.LOST || status == LaunchStatus.INTERRUPTED -> AttentionAmber
    else -> LocalPalette.current.error
}

private fun time(context: Context, ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, context.resources.configuration.locales[0]).format(Date(ms))

@Composable
private fun gameHint(context: Context, g: GameSummary): String =
    pluralStringResource(R.plurals.diag_launches, g.sessions, g.sessions) + " · " +
        stringResource(R.string.diag_last, time(context, g.lastPlayedMs), stringResource(statusLabel(g.lastStatus)))

@Composable
private fun launchHint(context: Context, s: SessionSummary): String {
    val parts = ArrayList<String>()
    s.durationMs?.let { parts += DateUtils.formatElapsedTime(it / 1000) }
    parts += stringResource(statusLabel(s.status))
    if (s.incomplete) parts += stringResource(R.string.diag_incomplete)
    return parts.joinToString(" · ")
}

private fun statusLabel(status: LaunchStatus): Int = when (status) {
    LaunchStatus.RUNNING -> R.string.diag_status_running
    LaunchStatus.EXITED -> R.string.diag_status_exited
    LaunchStatus.EXITED_WITH_ERROR -> R.string.diag_status_exited_with_error
    LaunchStatus.CRASHED -> R.string.diag_status_crashed
    LaunchStatus.KILLED -> R.string.diag_status_killed
    LaunchStatus.STOPPED -> R.string.diag_status_stopped
    LaunchStatus.LAUNCH_FAILED -> R.string.diag_status_launch_failed
    LaunchStatus.LOST -> R.string.diag_status_lost
    LaunchStatus.INTERRUPTED -> R.string.diag_status_interrupted
    LaunchStatus.UNKNOWN -> R.string.diag_status_unknown
}

private fun certaintyLabel(id: String): Int = when (id) {
    "confirmed" -> R.string.diag_certainty_confirmed
    "evidence" -> R.string.diag_certainty_evidence
    "ambiguous" -> R.string.diag_certainty_ambiguous
    else -> R.string.diag_certainty_inferred
}

private fun detailLabel(d: DiagnosticsPrefs.Detail): Int = when (d) {
    DiagnosticsPrefs.Detail.MINIMAL -> R.string.diag_detail_minimal
    DiagnosticsPrefs.Detail.NORMAL -> R.string.diag_detail_normal
    DiagnosticsPrefs.Detail.VERBOSE -> R.string.diag_detail_verbose
}
