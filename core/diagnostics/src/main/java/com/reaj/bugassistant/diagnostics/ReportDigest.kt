package com.reaj.bugassistant.diagnostics

import java.util.Locale

/**
 * Compact, plain-English description of a [DeviceReport] for the on-device LLM.
 * Model input, not UI text, so it is never localized. Bounded by [maxChars] to fit the context window.
 */
object ReportDigest {
    const val DEFAULT_MAX_CHARS = 6_000
    private const val FRAMES_PER_ISSUE = 6

    fun build(report: DeviceReport, maxChars: Int = DEFAULT_MAX_CHARS): String {
        val sb = StringBuilder()
        sb.appendLine("Device: ${report.device}")
        if (report.access == AccessLevel.BUG_REPORT) {
            sb.appendLine("Source: imported bug report covering about ${hours(report.windowMillis)} hours before capture.")
        } else {
            sb.appendLine("Window: last ${hours(report.windowMillis)} hours. Data source: ${source(report.access)}.")
        }
        report.appFilter?.let {
            sb.appendLine(
                "Focus app: $it. Only this app's issues are listed; memory, CPU, storage and thermal data are device-wide.",
            )
        }
        report.memory?.let { sb.appendLine(memoryLine(it)) }
        report.health?.let { healthLines(it).forEach(sb::appendLine) }
        sb.appendLine(
            "Totals: ${report.count(IssueKind.CRASH)} app crashes, " +
                "${report.count(IssueKind.NATIVE_CRASH)} native crashes, " +
                "${report.count(IssueKind.ANR)} ANRs, " +
                "${report.count(IssueKind.MEMORY_LEAK)} memory-leak signals, " +
                "${report.count(IssueKind.LOW_MEMORY_KILL)} low-memory kills, " +
                "${report.count(IssueKind.LOW_MEMORY)} system low-memory events.",
        )

        val kills = report.groups.filter { it.kind == IssueKind.LOW_MEMORY_KILL }
        if (kills.isNotEmpty()) {
            sb.appendLine("Low-memory kills by process: " + kills.take(10).joinToString(", ") { g ->
                "${g.process} x${g.count}" + if (g.totalFreedKb > 0) " (freed ${mb(g.totalFreedKb * 1024)})" else ""
            } + if (kills.size > 10) ", and ${kills.size - 10} more" else "")
        }

        val issues = report.groups.filter { it.kind != IssueKind.LOW_MEMORY_KILL && it.kind != IssueKind.LOW_MEMORY }
        var omitted = 0
        issues.forEachIndexed { i, g ->
            val block = issueBlock(i + 1, g, report.collectedAtMillis)
            if (sb.length + block.length > maxChars) omitted++ else sb.append(block)
        }
        if (omitted > 0) sb.appendLine("($omitted more issue groups omitted for length.)")
        if (issues.isEmpty()) sb.appendLine("No crashes, ANRs or memory-leak signals were recorded in this window.")
        return sb.toString().trimEnd()
    }

    private fun issueBlock(n: Int, g: IssueGroup, now: Long): String = buildString {
        val i = g.latest
        appendLine()
        appendLine("Issue $n: ${label(g.kind)} in ${g.process}, ${g.count} time(s), last ${ago(now - g.lastSeenMillis)}")
        appendLine("  Type: ${i.type}")
        i.message?.takeIf { it != i.type }?.let { appendLine("  Message: $it") }
        i.cause?.let { appendLine(if (g.kind == IssueKind.ANR) "  Main thread: $it" else "  Root cause: $it") }
        if (i.frames.isNotEmpty()) {
            appendLine("  Top frames:")
            i.frames.take(FRAMES_PER_ISSUE).forEach { appendLine("    $it") }
        }
    }

    private fun label(kind: IssueKind) = when (kind) {
        IssueKind.CRASH -> "App crash"
        IssueKind.NATIVE_CRASH -> "Native crash"
        IssueKind.ANR -> "ANR"
        IssueKind.LOW_MEMORY_KILL -> "Low-memory kill"
        IssueKind.LOW_MEMORY -> "System low memory"
        IssueKind.MEMORY_LEAK -> "Memory leak suspect"
    }

    private fun source(access: AccessLevel) = when (access) {
        AccessLevel.FULL -> "full system crash reports"
        AccessLevel.LOGS_ONLY -> "system logs (no ANR stack traces)"
        AccessLevel.OWN_APP -> "only this app's own history (limited)"
        AccessLevel.BUG_REPORT -> "imported bug report"
    }

    private fun hours(ms: Long) = (ms + 1_800_000) / 3_600_000

    private fun memoryLine(m: MemorySnapshot): String {
        val swap = m.swapUsedBytes?.let { used -> ", swap used ${mb(used)} of ${mb(m.swapTotalBytes ?: 0)}" } ?: ""
        val low = m.thresholdBytes?.let {
            ", low-memory threshold ${mb(it)}, currently low on memory: ${if (m.lowMemory) "yes" else "no"}"
        } ?: ""
        return "Memory: ${mb(m.availableBytes)} available of ${mb(m.totalBytes)} (${m.availablePercent}%)$low$swap."
    }

    private fun healthLines(h: DeviceHealth): List<String> = buildList {
        h.uptime?.let { add("Uptime: $it.") }
        if (h.cpuLoad.isNotEmpty() || h.cpuTotalPercent != null || h.topCpu.isNotEmpty()) {
            add(
                buildString {
                    append("CPU:")
                    if (h.cpuLoad.isNotEmpty()) append(" load average (1/5/15 min) ${h.cpuLoad.joinToString(" / ") { num(it) }};")
                    h.cpuTotalPercent?.let { append(" total use ${num(it)}%;") }
                    if (h.topCpu.isNotEmpty()) {
                        append(" busiest: ${h.topCpu.take(5).joinToString(", ") { "${it.process} ${num(it.value)}%" }}")
                    }
                }.trimEnd(';') + ".",
            )
        }
        h.thermalStatus?.let { add("Thermal status: ${thermalLabel(it)} ($it of 6).") }
        if (h.storageFreeBytes != null && h.storageTotalBytes != null) {
            val low = if ((h.storageFreePercent ?: 100) < 10) " (low)" else ""
            add("Storage: ${mb(h.storageFreeBytes)} free of ${mb(h.storageTotalBytes)} (${h.storageFreePercent}%)$low.")
        }
        if (h.topMemory.isNotEmpty()) {
            add("Largest memory users: ${h.topMemory.take(6).joinToString(", ") { "${it.process} ${mb(it.value.toLong() * 1024)}" }}.")
        }
        if (h.jank.isNotEmpty()) {
            add(
                "UI jank (frames skipped on the main thread): " +
                    h.jank.take(6).joinToString(", ") { "${it.process} ${it.value.toLong()} frames in ${it.events} reports" } + ".",
            )
        }
    }

    /** PowerManager.THERMAL_STATUS_* names. */
    fun thermalLabel(status: Int) = when (status) {
        0 -> "none"
        1 -> "light"
        2 -> "moderate, the device may throttle"
        3 -> "severe, the device is throttling"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> "unknown"
    }

    /**
     * Plain-text report for sharing (email, chat, ticket). Longer than the model digest, and includes the
     * AI summary when there is one. Not localized, like the digest.
     */
    fun shareText(report: DeviceReport, summary: String?, modelName: String?): String = buildString {
        appendLine("BugAssistant report")
        report.sourceName?.let { appendLine("File: $it") }
        report.appFilter?.let { appendLine("App: $it") }
        appendLine()
        if (!summary.isNullOrBlank()) {
            appendLine("AI summary" + (modelName?.let { " ($it, on-device)" } ?: "") + ":")
            appendLine(summary.trim())
            appendLine()
        }
        appendLine("Details:")
        append(build(report, maxChars = SHARE_MAX_CHARS))
    }

    private const val SHARE_MAX_CHARS = 40_000

    private fun num(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

    private fun mb(bytes: Long) = if (bytes >= 1L shl 30) {
        String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    } else {
        "${bytes / (1L shl 20)} MB"
    }

    private fun ago(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(0)
        return when {
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} days ago"
        }
    }
}
