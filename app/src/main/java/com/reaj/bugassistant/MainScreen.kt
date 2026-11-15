package com.reaj.bugassistant

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.reaj.bugassistant.diagnostics.AccessLevel
import com.reaj.bugassistant.diagnostics.DeviceHealth
import com.reaj.bugassistant.diagnostics.DeviceReport
import com.reaj.bugassistant.diagnostics.Issue
import com.reaj.bugassistant.diagnostics.IssueGroup
import com.reaj.bugassistant.diagnostics.IssueKind
import com.reaj.bugassistant.diagnostics.MemorySnapshot
import com.reaj.bugassistant.diagnostics.ProcessStat
import com.reaj.bugassistant.model.ModelChoice
import com.reaj.bugassistant.storage.ReportListItem
import com.reaj.bugassistant.ui.theme.BugAssistantTheme
import kotlinx.coroutines.delay

/** Stateless UI: renders [state] and reports clicks. Has no ViewModel, JNI or I/O. */
@Composable
fun MainScreen(
    state: MainUiState,
    packageName: String,
    onRetryModel: () -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onAnalyze: () -> Unit,
    onStop: () -> Unit,
    onShare: () -> Unit,
    onOpenSaved: (Long) -> Unit,
    onDeleteSaved: (Long) -> Unit,
    onAppFilter: (String?) -> Unit,
    onExport: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val report = state.visibleReport
    LazyColumn(
        modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(state, onRetryModel, onScan, onImport, onAnalyze, onStop, onShare) }
        if (state.appChoices.isNotEmpty()) {
            item { AppFilter(state.appFilter, state.appChoices, enabled = !state.scanning, onAppFilter) }
        }

        if (report != null) {
            if (report.access != AccessLevel.FULL && report.access != AccessLevel.BUG_REPORT) {
                item { AccessCard(report.access, packageName, onOpenUsageAccess) }
            }
            item { OverviewCard(report) }
            report.health?.let { health -> item { HealthCard(health) } }
            item { SummaryCard(state, report) }
            if (report.groups.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.issues_title), style = MaterialTheme.typography.titleMedium)
                }
                items(report.groups, key = { it.signature }) { IssueCard(it, report.collectedAtMillis) }
            }
            if (report.notes.isNotEmpty()) {
                item {
                    Text(
                        report.notes.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.history.isNotEmpty()) {
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.history_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onExport, enabled = state.export != ExportStatus.Running) {
                            Text(stringResource(R.string.action_export_csv))
                        }
                    }
                    exportStatusText(state.export)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.export is ExportStatus.Failed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(state.history, key = { "saved-${it.id}" }) { item ->
                SavedReportRow(item, selected = item.id == state.currentReportId, enabled = !state.scanning, onOpenSaved, onDeleteSaved)
            }
        }
    }
}

@Composable
private fun exportStatusText(status: ExportStatus): String? = when (status) {
    ExportStatus.Idle -> null
    ExportStatus.Running -> stringResource(R.string.export_running)
    is ExportStatus.Done -> pluralStringResource(R.plurals.export_done, status.rows, status.rows, status.fileName.orEmpty())
    is ExportStatus.Failed -> stringResource(R.string.export_failed, status.reason.orEmpty())
}

@Composable
private fun AppFilter(selected: String?, choices: List<String>, enabled: Boolean, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.filter_label, selected ?: stringResource(R.string.filter_all_apps)),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text("▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_all_apps)) },
                onClick = { expanded = false; onSelect(null) },
            )
            choices.forEach { app ->
                DropdownMenuItem(
                    text = { Text(app, fontWeight = if (app == selected) FontWeight.Bold else null) },
                    onClick = { expanded = false; onSelect(app) },
                )
            }
        }
    }
}

@Composable
private fun Header(
    state: MainUiState,
    onRetryModel: () -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onAnalyze: () -> Unit,
    onStop: () -> Unit,
    onShare: () -> Unit,
) {
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.built_with_llama),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        state.model?.let { ModelLine(it) }
        Text(modelStatusText(state.modelStatus), style = MaterialTheme.typography.bodyMedium)
        when (val scan = state.scan) {
            ScanStatus.Scanning -> {
                Text(stringResource(R.string.scan_running), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is ScanStatus.Importing -> {
                Text(stringResource(R.string.import_running, scan.fileName), style = MaterialTheme.typography.bodyMedium)
                val pct = scan.percent
                if (pct != null) {
                    LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            is ScanStatus.Failed -> Text(
                when {
                    scan.notABugReport -> stringResource(R.string.import_not_bug_report)
                    scan.import -> stringResource(R.string.import_failed, scan.reason ?: stringResource(R.string.error_unknown))
                    else -> stringResource(R.string.scan_failed, scan.reason ?: stringResource(R.string.error_unknown))
                },
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = onImport, enabled = state.canScan) {
                Text(stringResource(R.string.action_import))
            }
            OutlinedButton(onClick = onScan, enabled = state.canScan) {
                Text(stringResource(R.string.action_scan))
            }
            if (state.modelStatus is ModelStatus.Failed || state.modelStatus is ModelStatus.NotEnoughStorage) {
                Button(onClick = onRetryModel, enabled = !state.busy) {
                    Text(stringResource(R.string.action_retry))
                }
            } else {
                Button(onClick = onAnalyze, enabled = state.canAnalyze) {
                    Text(stringResource(R.string.action_analyze))
                }
            }
            OutlinedButton(onClick = onStop, enabled = state.generating) {
                Text(stringResource(R.string.action_stop))
            }
            OutlinedButton(onClick = onShare, enabled = state.canShare) {
                Text(stringResource(R.string.action_share))
            }
        }
    }
}

@Composable
private fun AccessCard(access: AccessLevel, packageName: String, onOpenUsageAccess: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (access == AccessLevel.OWN_APP) {
                Text(stringResource(R.string.access_own_app_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.access_own_app_body), style = MaterialTheme.typography.bodyMedium)
                SelectionContainer {
                    Text(
                        stringResource(R.string.access_adb_command, packageName),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Text(stringResource(R.string.access_logs_only_title), style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(R.string.access_usage_body), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenUsageAccess) { Text(stringResource(R.string.action_open_usage_access)) }
        }
    }
}

@Composable
private fun OverviewCard(report: DeviceReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (report.access == AccessLevel.BUG_REPORT) {
                Text(
                    stringResource(R.string.overview_bug_report, report.sourceName.orEmpty(), formatDateTime(report.collectedAtMillis)),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(report.device, style = MaterialTheme.typography.labelMedium)
            } else {
                Text(
                    stringResource(R.string.overview_window, report.device, report.windowMillis / DateUtils.HOUR_IN_MILLIS),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(report.count(IssueKind.CRASH), R.string.stat_crashes)
                Stat(report.count(IssueKind.NATIVE_CRASH), R.string.stat_native)
                Stat(report.count(IssueKind.ANR), R.string.stat_anrs)
                Stat(report.count(IssueKind.LOW_MEMORY_KILL), R.string.stat_lmk)
                Stat(report.count(IssueKind.MEMORY_LEAK), R.string.stat_leaks)
            }
            report.memory?.let { MemoryLines(it) }
        }
    }
}

@Composable
private fun Stat(value: Int, label: Int) {
    Column {
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = if (value > 0) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MemoryLines(m: MemorySnapshot) {
    val ctx = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(ctx, bytes)
    Text(
        stringResource(R.string.memory_ram, size(m.availableBytes), size(m.totalBytes), m.availablePercent),
        style = MaterialTheme.typography.bodyMedium,
    )
    m.swapUsedBytes?.takeIf { (m.swapTotalBytes ?: 0) > 0 }?.let { used ->
        Text(
            stringResource(R.string.memory_swap, size(used), size(m.swapTotalBytes ?: 0)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (m.lowMemory) {
        Text(
            m.thresholdBytes?.let { stringResource(R.string.memory_low_now, size(it)) }
                ?: stringResource(R.string.memory_low),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Device signals behind "the device is slow". Only bug reports have them. */
@Composable
private fun HealthCard(h: DeviceHealth) {
    val ctx = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(ctx, bytes)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.health_title), style = MaterialTheme.typography.titleMedium)
            h.uptime?.let { Text(stringResource(R.string.health_uptime, it), style = MaterialTheme.typography.bodyMedium) }
            if (h.cpuLoad.isNotEmpty() || h.cpuTotalPercent != null) {
                val load = h.cpuLoad.joinToString(" / ") { "%.1f".format(it) }
                val total = h.cpuTotalPercent
                Text(
                    when {
                        total != null && load.isNotEmpty() -> stringResource(R.string.health_cpu_both, total, load)
                        total != null -> stringResource(R.string.health_cpu_total, total)
                        else -> stringResource(R.string.health_cpu_load, load)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if ((h.cpuTotalPercent ?: 0.0) >= HIGH_CPU_PERCENT) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
            h.thermalStatus?.let { t ->
                Text(
                    stringResource(R.string.health_thermal, stringResource(thermalLabel(t))),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (t >= 2) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
            val free = h.storageFreeBytes
            val total = h.storageTotalBytes
            if (free != null && total != null) {
                val pct = h.storageFreePercent ?: 0
                Text(
                    stringResource(R.string.health_storage, size(free), size(total), pct),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (pct < LOW_STORAGE_PERCENT) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
            ProcessList(R.string.health_top_cpu, h.topCpu.take(3)) { "%.0f%%".format(it.value) }
            ProcessList(R.string.health_top_memory, h.topMemory.take(3)) { size(it.value.toLong() * 1024) }
            ProcessList(R.string.health_jank, h.jank.take(3)) {
                pluralStringResource(R.plurals.health_frames, it.value.toInt(), it.value.toInt())
            }
        }
    }
}

@Composable
private fun ProcessList(title: Int, stats: List<ProcessStat>, value: @Composable (ProcessStat) -> String) {
    if (stats.isEmpty()) return
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    stats.forEach { s ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s.process, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
            Text(value(s), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private fun thermalLabel(status: Int) = when (status) {
    0 -> R.string.thermal_none
    1 -> R.string.thermal_light
    2 -> R.string.thermal_moderate
    3 -> R.string.thermal_severe
    4 -> R.string.thermal_critical
    5 -> R.string.thermal_emergency
    6 -> R.string.thermal_shutdown
    else -> R.string.thermal_unknown
}

@Composable
private fun SavedReportRow(
    item: ReportListItem,
    selected: Boolean,
    enabled: Boolean,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    Card(
        onClick = { onOpen(item.id) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (item.isBugReport) item.sourceName ?: stringResource(R.string.history_bug_report)
                    else stringResource(R.string.history_live_scan),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    "${formatDateTime(item.collectedAt)} · ${item.device}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    stringResource(
                        R.string.history_counts,
                        item.crashCount + item.nativeCrashCount,
                        item.anrCount,
                        item.lmkCount,
                        item.leakCount,
                    ) + if (item.hasSummary) " · " + stringResource(R.string.history_has_summary) else "",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = { onDelete(item.id) }, enabled = enabled) {
                Text(stringResource(R.string.action_delete))
            }
        }
    }
}

@Composable
private fun formatDateTime(millis: Long): String =
    DateUtils.formatDateTime(
        LocalContext.current,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )

private const val HIGH_CPU_PERCENT = 80.0
private const val LOW_STORAGE_PERCENT = 10

@Composable
private fun SummaryCard(state: MainUiState, report: DeviceReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.summary_title), style = MaterialTheme.typography.titleMedium)
            genStatsText(state.genStats)?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            if (state.generating && state.summary.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            val placeholder = when {
                state.summary.isNotEmpty() -> null
                !report.hasIssues && report.appFilter != null ->
                    stringResource(R.string.summary_no_issues_app, report.appFilter.orEmpty())
                !report.hasIssues -> stringResource(R.string.summary_no_issues)
                state.generating -> null
                !state.loaded -> stringResource(R.string.summary_waiting_model)
                else -> stringResource(R.string.summary_idle)
            }
            if (state.summary.isEmpty() && report.hasIssues && state.generating) {
                AnalyzingIndicator()
            } else if (placeholder != null) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SelectionContainer { Text(state.summary, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

/** Shown while the model reads the prompt, before the first token arrives. */
@Composable
private fun AnalyzingIndicator() {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            seconds++
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Column {
            Text(stringResource(R.string.summary_thinking, seconds), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.summary_thinking_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IssueCard(group: IssueGroup, now: Long) {
    val issue = group.latest
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(kindLabel(group.kind)),
                    style = MaterialTheme.typography.labelLarge,
                    color = when (group.kind) {
                        IssueKind.LOW_MEMORY_KILL -> Color.Unspecified
                        IssueKind.MEMORY_LEAK -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    },
                )
                Text(stringResource(R.string.issue_count, group.count), style = MaterialTheme.typography.labelLarge)
            }
            Text(group.process, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(issue.cause ?: issue.message ?: issue.type, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            if (issue.frames.isNotEmpty()) {
                Text(
                    issue.frames.take(3).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                )
            }
            if (group.totalFreedKb > 0) {
                Text(
                    stringResource(R.string.issue_freed, Formatter.formatShortFileSize(LocalContext.current, group.totalFreedKb * 1024)),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                stringResource(
                    R.string.issue_last_seen,
                    DateUtils.getRelativeTimeSpanString(group.lastSeenMillis, now, DateUtils.MINUTE_IN_MILLIS),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun kindLabel(kind: IssueKind) = when (kind) {
    IssueKind.CRASH -> R.string.kind_crash
    IssueKind.NATIVE_CRASH -> R.string.kind_native_crash
    IssueKind.ANR -> R.string.kind_anr
    IssueKind.LOW_MEMORY_KILL -> R.string.kind_lmk
    IssueKind.LOW_MEMORY -> R.string.kind_low_memory
    IssueKind.MEMORY_LEAK -> R.string.kind_memory_leak
}

@Composable
private fun modelStatusText(status: ModelStatus): String = when (status) {
    ModelStatus.Starting -> stringResource(R.string.status_starting)
    is ModelStatus.Downloading -> status.percent
        ?.let { stringResource(R.string.status_downloading_percent, it) }
        ?: stringResource(R.string.status_downloading)
    ModelStatus.Loading -> stringResource(R.string.status_loading)
    is ModelStatus.Ready -> stringResource(R.string.status_ready, status.loadMs)
    is ModelStatus.Failed -> stringResource(R.string.status_failed, status.reason ?: stringResource(R.string.error_unknown))
    is ModelStatus.NotEnoughStorage -> stringResource(
        R.string.status_no_storage,
        Formatter.formatShortFileSize(LocalContext.current, status.neededBytes),
    )
}

@Composable
private fun ModelLine(model: ModelInfo) {
    val reason = when (model.reason) {
        ModelChoice.Reason.LOW_RAM -> R.string.model_reason_low_ram
        ModelChoice.Reason.LOW_STORAGE -> R.string.model_reason_low_storage
        ModelChoice.Reason.CAPABLE_DEVICE, null -> null
    }
    Text(
        if (reason != null) stringResource(R.string.model_using_small, model.name, stringResource(reason))
        else stringResource(R.string.model_using, model.name),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun genStatsText(stats: GenStats): String? = when (stats) {
    GenStats.None -> null
    is GenStats.Done -> pluralStringResource(
        R.plurals.stats_done, stats.tokens, stats.ttftMs, stats.tokens, stats.tokensPerSec,
    )
    GenStats.NoOutput -> stringResource(R.string.stats_no_output)
    GenStats.Stopped -> stringResource(R.string.stats_stopped)
    is GenStats.Error -> stringResource(R.string.stats_error, stats.reason ?: stringResource(R.string.error_unknown))
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

private const val PREVIEW_NOW = 1_762_960_000_000L

// Sample device data and model output for previews only. They stand in for collected logs and
// generated text (not UI copy), so they stay in code rather than shipping in string resources.
private val PREVIEW_REPORT = DeviceReport(
    collectedAtMillis = PREVIEW_NOW,
    windowMillis = 72 * DateUtils.HOUR_IN_MILLIS,
    device = "Google Pixel 8, Android 16 (API 36)",
    access = AccessLevel.FULL,
    memory = MemorySnapshot(
        totalBytes = 8L shl 30, availableBytes = 1L shl 30, thresholdBytes = 216L shl 20,
        lowMemory = true, swapTotalBytes = 4L shl 30, swapFreeBytes = 1L shl 30,
    ),
    groups = listOf(
        IssueGroup(
            signature = "crash",
            latest = Issue(
                kind = IssueKind.CRASH, timeMillis = PREVIEW_NOW - 2 * DateUtils.HOUR_IN_MILLIS,
                process = "com.example.shop", type = "java.lang.RuntimeException",
                cause = "java.lang.NullPointerException: Attempt to invoke virtual method 'int java.util.List.size()'",
                frames = listOf("com.example.shop.CartActivity.onCreate(CartActivity.kt:57)"),
            ),
            count = 3, firstSeenMillis = PREVIEW_NOW - 30 * DateUtils.HOUR_IN_MILLIS,
            lastSeenMillis = PREVIEW_NOW - 2 * DateUtils.HOUR_IN_MILLIS,
        ),
        IssueGroup(
            signature = "anr",
            latest = Issue(
                kind = IssueKind.ANR, timeMillis = PREVIEW_NOW - 5 * DateUtils.HOUR_IN_MILLIS,
                process = "com.example.mail", type = "Input dispatching timed out",
                message = "Input dispatching timed out · main thread: Blocked",
                cause = "waiting to lock <0x0a1b2c3d> held by thread 23",
                frames = listOf("com.example.mail.db.Store.read(Store.kt:88)", "com.example.mail.Inbox.onResume(Inbox.kt:40)"),
            ),
            count = 1, firstSeenMillis = PREVIEW_NOW - 5 * DateUtils.HOUR_IN_MILLIS,
            lastSeenMillis = PREVIEW_NOW - 5 * DateUtils.HOUR_IN_MILLIS,
        ),
        IssueGroup(
            signature = "lmk",
            latest = Issue(
                kind = IssueKind.LOW_MEMORY_KILL, timeMillis = PREVIEW_NOW - DateUtils.HOUR_IN_MILLIS,
                process = "com.example.news", type = "Killed by low-memory killer", message = "oom_score_adj 905",
            ),
            count = 12, firstSeenMillis = PREVIEW_NOW - 60 * DateUtils.HOUR_IN_MILLIS,
            lastSeenMillis = PREVIEW_NOW - DateUtils.HOUR_IN_MILLIS, totalFreedKb = 950_000,
        ),
    ),
)

private const val PREVIEW_SUMMARY = "Overview: the device is under memory pressure and one app crashes repeatedly.\n" +
    "Top issues:\n" +
    "- com.example.shop crashes in CartActivity.onCreate with a NullPointerException on a null List. " +
    "Initialize the cart list before use.\n" +
    "- com.example.mail ANR: the main thread waits for a lock held by a database thread. Move Store.read off the main thread.\n" +
    "Memory: only 1 GB of 8 GB is free and lmkd killed com.example.news 12 times."

private val PREVIEW_DONE_STATE = MainUiState(
    modelStatus = ModelStatus.Ready(1840),
    model = ModelInfo("Llama 3.2 3B", ModelChoice.Reason.CAPABLE_DEVICE),
    scan = ScanStatus.Done(PREVIEW_REPORT),
    summary = PREVIEW_SUMMARY,
    genStats = GenStats.Done(ttftMs = 2100, tokens = 148, tokensPerSec = 17.9),
)

/** An imported bug report from a slow field device, with saved history. */
private val PREVIEW_BUG_REPORT_STATE = PREVIEW_DONE_STATE.copy(
    scan = ScanStatus.Done(
        PREVIEW_REPORT.copy(
            access = AccessLevel.BUG_REPORT,
            sourceName = "bugreport-field-tablet-2025-11-12.zip",
            memory = PREVIEW_REPORT.memory?.copy(thresholdBytes = null, lowMemory = false),
            health = DeviceHealth(
                uptime = "up 41 days, 3:12",
                cpuLoad = listOf(11.2, 9.8, 9.1),
                cpuTotalPercent = 93.0,
                topCpu = listOf(ProcessStat("com.example.sync", 61.0), ProcessStat("system_server", 18.0)),
                topMemory = listOf(ProcessStat("com.example.shop", 612_000.0), ProcessStat("system_server", 301_000.0)),
                storageFreeBytes = 900L shl 20,
                storageTotalBytes = 32L shl 30,
                thermalStatus = 3,
                jank = listOf(ProcessStat("com.example.shop", 1_420.0, events = 37)),
            ),
        ),
    ),
    currentReportId = 2,
    appFilter = "com.example.shop",
    history = listOf(
        ReportListItem(2, PREVIEW_NOW, "BUG_REPORT", "bugreport-field-tablet-2025-11-12.zip", "Acme Tab 10, Android 14 (API 34)", PREVIEW_NOW, 3, 0, 1, 12, 2, true),
        ReportListItem(1, PREVIEW_NOW - DateUtils.DAY_IN_MILLIS, "FULL", null, "Google Pixel 8, Android 16 (API 36)", PREVIEW_NOW - DateUtils.DAY_IN_MILLIS, 1, 0, 0, 2, 0, false),
    ),
)

/** One sample [MainUiState] for each stage of the app's lifecycle. */
private class MainUiStateProvider : PreviewParameterProvider<MainUiState> {
    override val values = sequenceOf(
        MainUiState(
            modelStatus = ModelStatus.Downloading(42),
            model = ModelInfo("Llama 3.2 1B", ModelChoice.Reason.LOW_RAM),
            scan = ScanStatus.Scanning,
            busy = true,
        ),
        MainUiState(
            modelStatus = ModelStatus.Loading,
            model = ModelInfo("Llama 3.2 3B", ModelChoice.Reason.CAPABLE_DEVICE),
            scan = ScanStatus.Importing("bugreport-field-tablet-2025-11-12.zip", 64),
        ),
        MainUiState(
            modelStatus = ModelStatus.NotEnoughStorage(1_200_000_000),
            model = ModelInfo("Llama 3.2 1B", null),
            scan = ScanStatus.Done(PREVIEW_REPORT.copy(access = AccessLevel.OWN_APP, groups = emptyList())),
        ),
        PREVIEW_DONE_STATE.copy(summary = PREVIEW_SUMMARY.take(80), genStats = GenStats.None, busy = true, generating = true),
        PREVIEW_DONE_STATE,
        PREVIEW_BUG_REPORT_STATE,
    )
}

@Preview(name = "States", showBackground = true, heightDp = 900)
@Composable
private fun MainScreenStatesPreview(
    @PreviewParameter(MainUiStateProvider::class) state: MainUiState,
) {
    MainScreenPreviewContent(state)
}

@PreviewLightDark
@Composable
private fun MainScreenThemePreview() {
    MainScreenPreviewContent(PREVIEW_DONE_STATE)
}

@Composable
private fun MainScreenPreviewContent(state: MainUiState) {
    BugAssistantTheme {
        Surface(Modifier.fillMaxSize()) {
            MainScreen(
                state = state,
                packageName = "com.reaj.bugassistant",
                onRetryModel = {},
                onScan = {},
                onImport = {},
                onAnalyze = {},
                onStop = {},
                onShare = {},
                onOpenSaved = {},
                onDeleteSaved = {},
                onAppFilter = {},
                onExport = {},
                onOpenUsageAccess = {},
            )
        }
    }
}
