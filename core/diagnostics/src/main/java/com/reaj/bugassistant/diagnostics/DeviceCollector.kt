package com.reaj.bugassistant.diagnostics

import android.Manifest
import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.DropBoxManager
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Collects crashes, ANRs, native crashes and memory pressure from the device.
 *
 * Sources, best first (see [AccessLevel]):
 *  1. DropBox: full crash reports, ANR traces and tombstones for every app (READ_LOGS + usage access).
 *  2. logcat crash/events/main buffers (READ_LOGS).
 *  3. ApplicationExitInfo for this app only (no permission, API 30+).
 * Memory comes from ActivityManager and /proc/meminfo, which need no permission.
 */
class DeviceCollector(private val context: Context) {

    fun hasReadLogs(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    /** DropBox also requires the GET_USAGE_STATS app-op, granted from Settings > Usage access. */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED ||
            (mode == AppOpsManager.MODE_DEFAULT &&
                context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED)
    }

    suspend fun collect(windowMillis: Long = DEFAULT_WINDOW_MS): DeviceReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val since = now - windowMillis
        val notes = mutableListOf<String>()
        val issues = mutableListOf<Issue>()

        val readLogs = hasReadLogs()
        val dropBox = if (readLogs && hasUsageAccess()) {
            runCatching { readDropBox(since) }.onFailure { notes += "DropBox: ${it.message}" }.getOrNull()
        } else {
            null
        }

        val access = when {
            dropBox != null -> AccessLevel.FULL
            readLogs -> AccessLevel.LOGS_ONLY
            else -> AccessLevel.OWN_APP
        }
        when (access) {
            AccessLevel.FULL -> {
                issues += dropBox.orEmpty()
                issues += logcatIssues(listOf("-b", "main", "-s", "lowmemorykiller"), now, notes)
            }
            AccessLevel.LOGS_ONLY -> {
                issues += logcatIssues(listOf("-b", "crash"), now, notes)
                issues += logcatIssues(listOf("-b", "events", "-s", "am_anr"), now, notes)
                issues += logcatIssues(listOf("-b", "main", "-s", "lowmemorykiller"), now, notes)
            }
            AccessLevel.OWN_APP -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    runCatching { issues += readExitInfo() }.onFailure { notes += "Exit info: ${it.message}" }
                } else {
                    issues += logcatIssues(listOf("-b", "crash"), now, notes)   // own UID only
                }
            }
            AccessLevel.BUG_REPORT -> error("Live scans never produce BUG_REPORT")
        }

        DeviceReport(
            collectedAtMillis = now,
            windowMillis = windowMillis,
            device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            access = access,
            memory = runCatching { readMemory() }.onFailure { notes += "Memory: ${it.message}" }.getOrNull(),
            groups = IssueGrouper.group(issues.filter { it.timeMillis >= since }),
            notes = notes,
        )
    }

    private fun readDropBox(since: Long): List<Issue> {
        val dbm = context.getSystemService(DropBoxManager::class.java) ?: error("DropBoxManager unavailable")
        val out = mutableListOf<Issue>()
        var cursor = since
        var scanned = 0
        while (scanned++ < MAX_DROPBOX_ENTRIES && out.size < MAX_ISSUES) {
            val entry = dbm.getNextEntry(null, cursor) ?: break
            entry.use { e ->
                cursor = e.timeMillis
                val kind = CrashParser.kindForDropBoxTag(e.tag ?: "") ?: return@use
                val text = e.getText(MAX_ENTRY_CHARS) ?: return@use
                out += CrashParser.parse(kind, e.timeMillis, text)
            }
        }
        return out
    }

    private fun logcatIssues(args: List<String>, now: Long, notes: MutableList<String>): List<Issue> =
        runCatching {
            val proc = ProcessBuilder(listOf("logcat", "-d", "-v", "epoch") + args)
                .redirectErrorStream(true)
                .start()
            try {
                val text = proc.inputStream.bufferedReader().useLines { lines ->
                    lines.take(MAX_LOGCAT_LINES).joinToString("\n")
                }
                proc.waitFor(5, TimeUnit.SECONDS)
                LogcatParser.issues(LogcatParser.parseLines(text, now))
            } finally {
                proc.destroy()
            }
        }.onFailure { notes += "logcat ${args.joinToString(" ")}: ${it.message}" }.getOrDefault(emptyList())

    @TargetApi(30)
    private fun readExitInfo(): List<Issue> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return am.getHistoricalProcessExitReasons(null, 0, 0).mapNotNull { info ->
            val kind = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH -> IssueKind.CRASH
                ApplicationExitInfo.REASON_CRASH_NATIVE -> IssueKind.NATIVE_CRASH
                ApplicationExitInfo.REASON_ANR -> IssueKind.ANR
                ApplicationExitInfo.REASON_LOW_MEMORY -> IssueKind.LOW_MEMORY_KILL
                else -> return@mapNotNull null
            }
            val description = info.description
            if (kind == IssueKind.ANR) {
                val trace = runCatching {
                    info.traceInputStream?.bufferedReader()?.use { r ->
                        // ANR traces dump every thread; the main thread comes first.
                        val buf = CharArray(MAX_ENTRY_CHARS)
                        var n = 0
                        while (n < buf.size) {
                            val read = r.read(buf, n, buf.size - n)
                            if (read < 0) break
                            n += read
                        }
                        String(buf, 0, n)
                    }
                }.getOrNull()
                if (trace != null) {
                    return@mapNotNull CrashParser.parseAnr(info.timestamp, trace, reason = description)
                        .copy(process = info.processName, pid = info.pid)
                }
            }
            Issue(
                kind = kind,
                timeMillis = info.timestamp,
                process = info.processName,
                pid = info.pid,
                type = when (kind) {
                    IssueKind.ANR -> description?.let(CrashParser::normalizeAnrReason) ?: "ANR"
                    IssueKind.LOW_MEMORY_KILL -> "Killed by low-memory killer"
                    else -> description?.substringBefore(':')?.take(120) ?: kind.name
                },
                message = description?.take(300),
                freedKb = if (kind == IssueKind.LOW_MEMORY_KILL) info.rss.takeIf { it > 0 } else null,
            )
        }
    }

    private fun readMemory(): MemorySnapshot {
        val am = context.getSystemService(ActivityManager::class.java) ?: error("ActivityManager unavailable")
        val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val meminfo = runCatching { MeminfoParser.parse(File("/proc/meminfo").readText()) }.getOrDefault(emptyMap())
        return MemorySnapshot(
            totalBytes = mi.totalMem,
            availableBytes = mi.availMem,
            thresholdBytes = mi.threshold,
            lowMemory = mi.lowMemory,
            swapTotalBytes = meminfo["SwapTotal"]?.times(1024),
            swapFreeBytes = meminfo["SwapFree"]?.times(1024),
        )
    }

    companion object {
        /** DropBox keeps about three days of entries by default. */
        val DEFAULT_WINDOW_MS = TimeUnit.DAYS.toMillis(3)
        private const val MAX_DROPBOX_ENTRIES = 2_000
        private const val MAX_ISSUES = 500
        private const val MAX_ENTRY_CHARS = 64 * 1024
        private const val MAX_LOGCAT_LINES = 20_000
    }
}
