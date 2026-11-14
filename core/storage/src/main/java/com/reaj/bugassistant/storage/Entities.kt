package com.reaj.bugassistant.storage

import androidx.room.DatabaseView
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One saved report: a live scan or an imported bug report. AI summaries are in [SummaryEntity]. */
@Entity(tableName = "reports", indices = [Index("fingerprint")])
data class ReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val savedAt: Long,
    /** [com.reaj.bugassistant.diagnostics.AccessLevel] name; BUG_REPORT for imports. */
    val access: String,
    val sourceName: String?,
    val device: String,
    val collectedAt: Long,
    val windowMillis: Long,
    /** Identifies the same data saved twice (re-imported file, unchanged live scan). */
    val fingerprint: String,
    val crashCount: Int,
    val nativeCrashCount: Int,
    val anrCount: Int,
    val lmkCount: Int,
    val lowMemoryCount: Int,
    @Embedded(prefix = "mem_") val memory: MemoryColumns?,
    val hasHealth: Boolean,
    @Embedded(prefix = "health_") val health: HealthColumns?,
    /** Newline-separated. */
    val notes: String,
)

data class MemoryColumns(
    val totalBytes: Long,
    val availableBytes: Long,
    val thresholdBytes: Long?,
    val lowMemory: Boolean,
    val swapTotalBytes: Long?,
    val swapFreeBytes: Long?,
)

data class HealthColumns(
    val uptime: String?,
    /** Comma-separated load averages. */
    val cpuLoad: String?,
    val cpuTotalPercent: Double?,
    val storageFreeBytes: Long?,
    val storageTotalBytes: Long?,
    val thermalStatus: Int?,
)

@Entity(
    tableName = "issue_groups",
    foreignKeys = [ForeignKey(ReportEntity::class, ["id"], ["reportId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("reportId"), Index("packageName")],
)
data class IssueGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reportId: Long,
    /** Order within the report (most impactful first). */
    val position: Int,
    val signature: String,
    val kind: String,
    /** App package (process name without `:suffix`), for filtering by app. */
    val packageName: String,
    val count: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val totalFreedKb: Long,
    // Latest occurrence:
    val time: Long,
    val process: String,
    val pid: Int?,
    val type: String,
    val message: String?,
    val cause: String?,
    /** Newline-separated, innermost first. */
    val frames: String,
    val freedKb: Long?,
)

@Entity(
    tableName = "process_stats",
    foreignKeys = [ForeignKey(ReportEntity::class, ["id"], ["reportId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("reportId")],
)
data class ProcessStatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reportId: Long,
    /** CPU, MEMORY or JANK. */
    val category: String,
    val position: Int,
    val process: String,
    val value: Double,
    val events: Int,
)

/** AI summary of a report, either for all apps ([app] = "") or for one app package. */
@Entity(
    tableName = "summaries",
    primaryKeys = ["reportId", "app"],
    foreignKeys = [ForeignKey(ReportEntity::class, ["id"], ["reportId"], onDelete = ForeignKey.CASCADE)],
)
data class SummaryEntity(
    val reportId: Long,
    val app: String,
    val text: String,
    val model: String?,
    val createdAt: Long,
)

/**
 * Flat, query-friendly view for developers inspecting the database, e.g.
 * `SELECT * FROM app_issues WHERE packageName = 'com.acme.field' ORDER BY lastSeen DESC`.
 */
@DatabaseView(
    viewName = "app_issues",
    value = "SELECT g.packageName, g.process, g.kind, g.count, g.type, g.message, g.cause, g.frames, " +
        "g.firstSeen, g.lastSeen, r.id AS reportId, r.access, r.sourceName, r.device, r.collectedAt " +
        "FROM issue_groups g JOIN reports r ON r.id = g.reportId",
)
data class AppIssueView(
    val packageName: String,
    val process: String,
    val kind: String,
    val count: Int,
    val type: String,
    val message: String?,
    val cause: String?,
    val frames: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val reportId: Long,
    val access: String,
    val sourceName: String?,
    val device: String,
    val collectedAt: Long,
)

/** An app seen in saved reports, for the app filter. */
data class AppCount(
    val packageName: String,
    /** Saved reports that contain this app. */
    val reports: Int,
    /** Crashes, native crashes, ANRs and low-memory kills across those reports. */
    val issues: Int,
)

/** Row for the saved-reports list. Counts follow the app filter it was queried with. */
data class ReportListItem(
    val id: Long,
    val savedAt: Long,
    val access: String,
    val sourceName: String?,
    val device: String,
    val collectedAt: Long,
    val crashCount: Int,
    val nativeCrashCount: Int,
    val anrCount: Int,
    val lmkCount: Int,
    val leakCount: Int,
    val hasSummary: Boolean,
) {
    val isBugReport: Boolean get() = access == "BUG_REPORT"
}
