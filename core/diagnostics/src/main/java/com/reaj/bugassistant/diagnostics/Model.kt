package com.reaj.bugassistant.diagnostics

enum class IssueKind {
    CRASH, NATIVE_CRASH, ANR, LOW_MEMORY_KILL, LOW_MEMORY,

    /**
     * An app showing signs of a memory leak (bug reports only): its Java heap hit or neared its limit,
     * it threw OutOfMemoryError, or it holds more Activity objects than it could be showing.
     */
    MEMORY_LEAK,
}

/** One occurrence of a problem, parsed from a single log record. */
data class Issue(
    val kind: IssueKind,
    val timeMillis: Long,
    val process: String,
    val pid: Int? = null,
    /** Exception type, signal or ANR reason. Stable across occurrences, so it is used for grouping. */
    val type: String,
    /** First error line. May contain per-occurrence details (ids, addresses). */
    val message: String? = null,
    /** Root "Caused by:" line for Java crashes, or the lock being waited on for ANRs. */
    val cause: String? = null,
    /** Top frames of the crashing (or main) thread, innermost first. */
    val frames: List<String> = emptyList(),
    /** Memory freed by a low-memory kill. */
    val freedKb: Long? = null,
)

/** Occurrences that share a signature (kind, process, root type, first app frame). */
data class IssueGroup(
    val signature: String,
    /** The most recent occurrence. */
    val latest: Issue,
    val count: Int,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val totalFreedKb: Long = 0,
) {
    val kind: IssueKind get() = latest.kind
    val process: String get() = latest.process
}

data class MemorySnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    /**
     * Below this, the system considers itself low on memory and starts killing background apps.
     * Null when unknown (bug reports do not record it).
     */
    val thresholdBytes: Long? = null,
    val lowMemory: Boolean = false,
    val swapTotalBytes: Long? = null,
    val swapFreeBytes: Long? = null,
) {
    val availablePercent: Int
        get() = if (totalBytes > 0) (availableBytes * 100 / totalBytes).toInt() else 0
    val swapUsedBytes: Long?
        get() = if (swapTotalBytes != null && swapFreeBytes != null) swapTotalBytes - swapFreeBytes else null
}

enum class AccessLevel {
    /** READ_LOGS + usage access: DropBox (crashes, ANR traces, tombstones) and logcat. */
    FULL,
    /** READ_LOGS only: logcat crash/events/main buffers. No ANR stack traces. */
    LOGS_ONLY,
    /** No privileged access: this app's own exit history (API 30+). */
    OWN_APP,
    /** An imported Android bug report: every app, plus device performance data. */
    BUG_REPORT,
}

/** A process and a measured value: CPU percent, memory in kB, or skipped frames. */
data class ProcessStat(
    val process: String,
    val value: Double,
    /** Number of events behind [value] (e.g. jank reports), when meaningful. */
    val events: Int = 0,
)

/** Device-wide signals that explain slowness. Only bug reports carry these. */
data class DeviceHealth(
    /** As printed by the device, e.g. "up 3 days, 2:10". */
    val uptime: String? = null,
    /** 1, 5 and 15-minute load averages. */
    val cpuLoad: List<Double> = emptyList(),
    /** Total CPU use over the sampled period, in percent. */
    val cpuTotalPercent: Double? = null,
    /** Busiest processes, highest first; value = CPU %. */
    val topCpu: List<ProcessStat> = emptyList(),
    /** Largest processes, highest first; value = memory in kB (PSS or RSS). */
    val topMemory: List<ProcessStat> = emptyList(),
    val storageFreeBytes: Long? = null,
    val storageTotalBytes: Long? = null,
    /** PowerManager thermal status: 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown. */
    val thermalStatus: Int? = null,
    /** UI jank by process; value = skipped frames, events = jank reports. Highest first. */
    val jank: List<ProcessStat> = emptyList(),
) {
    val storageFreePercent: Int?
        get() = if (storageFreeBytes != null && storageTotalBytes != null && storageTotalBytes > 0) {
            (storageFreeBytes * 100 / storageTotalBytes).toInt()
        } else {
            null
        }
}

data class DeviceReport(
    val collectedAtMillis: Long,
    /** Time span covered by the data, ending at [collectedAtMillis]. */
    val windowMillis: Long,
    val device: String,
    val access: AccessLevel,
    val memory: MemorySnapshot?,
    /** Sorted most impactful first. */
    val groups: List<IssueGroup>,
    /** Non-fatal problems hit while collecting (a source that failed, etc.). Not localized. */
    val notes: List<String> = emptyList(),
    /** Imported file name, for bug reports. */
    val sourceName: String? = null,
    val health: DeviceHealth? = null,
    /** Package the issues were narrowed to by [forApp]; null = all apps. */
    val appFilter: String? = null,
) {
    fun count(kind: IssueKind): Int = groups.filter { it.kind == kind }.sumOf { it.count }
    val hasIssues: Boolean get() = groups.isNotEmpty()

    /** Packages with app-level issues, most affected first. */
    val apps: List<String>
        get() = groups.filter { it.kind != IssueKind.LOW_MEMORY }
            .groupBy { it.packageName }
            .entries.sortedByDescending { e -> e.value.sumOf { it.count } }
            .map { it.key }

    /**
     * Only the issues of [packageName] (all of its processes, e.g. `com.acme:sync`). Device-wide
     * low-memory events, memory and health data are kept, since they explain the app's problems too.
     */
    fun forApp(packageName: String?): DeviceReport =
        if (packageName.isNullOrBlank()) {
            copy(appFilter = null)
        } else {
            copy(
                groups = groups.filter { it.kind == IssueKind.LOW_MEMORY || it.packageName == packageName },
                appFilter = packageName,
            )
        }
}

/** `com.acme.app:remote` -> `com.acme.app`. Native processes keep their name. */
fun packageOf(process: String): String = process.substringBefore(':').trim()

val IssueGroup.packageName: String get() = packageOf(process)
