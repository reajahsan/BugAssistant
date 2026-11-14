package com.reaj.bugassistant.storage

import com.reaj.bugassistant.diagnostics.AccessLevel
import com.reaj.bugassistant.diagnostics.DeviceHealth
import com.reaj.bugassistant.diagnostics.DeviceReport
import com.reaj.bugassistant.diagnostics.Issue
import com.reaj.bugassistant.diagnostics.IssueGroup
import com.reaj.bugassistant.diagnostics.IssueKind
import com.reaj.bugassistant.diagnostics.MemorySnapshot
import com.reaj.bugassistant.diagnostics.ProcessStat
import com.reaj.bugassistant.diagnostics.packageName
import java.security.MessageDigest

/** Rows for one report, before ids are assigned. */
data class ReportRows(
    val report: ReportEntity,
    val groups: List<IssueGroupEntity>,
    val stats: List<ProcessStatEntity>,
)

/** A report loaded back from the database. Summaries are loaded separately, per app filter. */
data class SavedReport(
    val id: Long,
    val savedAtMillis: Long,
    val report: DeviceReport,
)

object ReportMapper {
    private const val CPU = "CPU"
    private const val MEMORY = "MEMORY"
    private const val JANK = "JANK"

    /**
     * Same source and same issues = same fingerprint. Live scans re-run on every launch, so this
     * stops identical scans (and re-imports of the same file) from piling up.
     */
    fun fingerprint(report: DeviceReport): String {
        val key = buildString {
            append(report.access.name).append('|')
            append(report.sourceName.orEmpty()).append('|')
            append(report.device).append('|')
            if (report.access == AccessLevel.BUG_REPORT) append(report.collectedAtMillis).append('|')
            report.groups.forEach { append(it.signature).append(':').append(it.count).append(':').append(it.lastSeenMillis).append(';') }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun toRows(report: DeviceReport, savedAt: Long): ReportRows {
        val entity = ReportEntity(
            savedAt = savedAt,
            access = report.access.name,
            sourceName = report.sourceName,
            device = report.device,
            collectedAt = report.collectedAtMillis,
            windowMillis = report.windowMillis,
            fingerprint = fingerprint(report),
            crashCount = report.count(IssueKind.CRASH),
            nativeCrashCount = report.count(IssueKind.NATIVE_CRASH),
            anrCount = report.count(IssueKind.ANR),
            lmkCount = report.count(IssueKind.LOW_MEMORY_KILL),
            lowMemoryCount = report.count(IssueKind.LOW_MEMORY),
            memory = report.memory?.let {
                MemoryColumns(it.totalBytes, it.availableBytes, it.thresholdBytes, it.lowMemory, it.swapTotalBytes, it.swapFreeBytes)
            },
            hasHealth = report.health != null,
            health = report.health?.let {
                HealthColumns(
                    uptime = it.uptime,
                    cpuLoad = it.cpuLoad.takeIf { l -> l.isNotEmpty() }?.joinToString(","),
                    cpuTotalPercent = it.cpuTotalPercent,
                    storageFreeBytes = it.storageFreeBytes,
                    storageTotalBytes = it.storageTotalBytes,
                    thermalStatus = it.thermalStatus,
                )
            },
            notes = report.notes.joinToString("\n"),
        )
        val groups = report.groups.mapIndexed { i, g ->
            val l = g.latest
            IssueGroupEntity(
                reportId = 0,
                position = i,
                signature = g.signature,
                kind = l.kind.name,
                packageName = g.packageName,
                count = g.count,
                firstSeen = g.firstSeenMillis,
                lastSeen = g.lastSeenMillis,
                totalFreedKb = g.totalFreedKb,
                time = l.timeMillis,
                process = l.process,
                pid = l.pid,
                type = l.type,
                message = l.message,
                cause = l.cause,
                frames = l.frames.joinToString("\n"),
                freedKb = l.freedKb,
            )
        }
        val stats = report.health?.let { h ->
            stats(CPU, h.topCpu) + stats(MEMORY, h.topMemory) + stats(JANK, h.jank)
        }.orEmpty()
        return ReportRows(entity, groups, stats)
    }

    private fun stats(category: String, list: List<ProcessStat>) = list.mapIndexed { i, s ->
        ProcessStatEntity(reportId = 0, category = category, position = i, process = s.process, value = s.value, events = s.events)
    }

    fun fromRows(report: ReportEntity, groups: List<IssueGroupEntity>, stats: List<ProcessStatEntity>): SavedReport {
        val byCategory = stats.sortedBy { it.position }.groupBy({ it.category }) { ProcessStat(it.process, it.value, it.events) }
        val health = report.health?.takeIf { report.hasHealth }?.let {
            DeviceHealth(
                uptime = it.uptime,
                cpuLoad = it.cpuLoad?.split(',')?.mapNotNull(String::toDoubleOrNull).orEmpty(),
                cpuTotalPercent = it.cpuTotalPercent,
                topCpu = byCategory[CPU].orEmpty(),
                topMemory = byCategory[MEMORY].orEmpty(),
                storageFreeBytes = it.storageFreeBytes,
                storageTotalBytes = it.storageTotalBytes,
                thermalStatus = it.thermalStatus,
                jank = byCategory[JANK].orEmpty(),
            )
        } ?: if (report.hasHealth) {
            // Every embedded column was null: Room returns a null object.
            DeviceHealth(topCpu = byCategory[CPU].orEmpty(), topMemory = byCategory[MEMORY].orEmpty(), jank = byCategory[JANK].orEmpty())
        } else {
            null
        }
        val device = DeviceReport(
            collectedAtMillis = report.collectedAt,
            windowMillis = report.windowMillis,
            device = report.device,
            access = enumValueOrNull<AccessLevel>(report.access) ?: AccessLevel.OWN_APP,
            memory = report.memory?.let {
                MemorySnapshot(it.totalBytes, it.availableBytes, it.thresholdBytes, it.lowMemory, it.swapTotalBytes, it.swapFreeBytes)
            },
            groups = groups.sortedBy { it.position }.mapNotNull { g ->
                val kind = enumValueOrNull<IssueKind>(g.kind) ?: return@mapNotNull null
                IssueGroup(
                    signature = g.signature,
                    latest = Issue(
                        kind = kind,
                        timeMillis = g.time,
                        process = g.process,
                        pid = g.pid,
                        type = g.type,
                        message = g.message,
                        cause = g.cause,
                        frames = if (g.frames.isEmpty()) emptyList() else g.frames.split('\n'),
                        freedKb = g.freedKb,
                    ),
                    count = g.count,
                    firstSeenMillis = g.firstSeen,
                    lastSeenMillis = g.lastSeen,
                    totalFreedKb = g.totalFreedKb,
                )
            },
            notes = if (report.notes.isEmpty()) emptyList() else report.notes.split('\n'),
            sourceName = report.sourceName,
            health = health,
        )
        return SavedReport(report.id, report.savedAt, device)
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}
