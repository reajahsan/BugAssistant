package com.reaj.bugassistant.storage

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ReportDao {
    @Insert protected abstract suspend fun insertReport(report: ReportEntity): Long
    @Insert protected abstract suspend fun insertGroups(groups: List<IssueGroupEntity>)
    @Insert protected abstract suspend fun insertStats(stats: List<ProcessStatEntity>)

    @Transaction
    open suspend fun insert(report: ReportEntity, groups: List<IssueGroupEntity>, stats: List<ProcessStatEntity>): Long {
        val id = insertReport(report)
        insertGroups(groups.map { it.copy(reportId = id) })
        insertStats(stats.map { it.copy(reportId = id) })
        return id
    }

    @Query("SELECT * FROM reports WHERE id = :id")
    abstract suspend fun report(id: Long): ReportEntity?

    @Query("SELECT * FROM issue_groups WHERE reportId = :id ORDER BY position")
    abstract suspend fun groups(id: Long): List<IssueGroupEntity>

    @Query("SELECT * FROM process_stats WHERE reportId = :id ORDER BY category, position")
    abstract suspend fun stats(id: Long): List<ProcessStatEntity>

    /**
     * Saved reports, newest first. With [app], only reports that contain that app, with counts and the
     * summary flag for that app only. Null [app] = all apps.
     */
    @Query(
        """
        SELECT r.id, r.savedAt, r.access, r.sourceName, r.device, r.collectedAt,
            COALESCE((SELECT SUM(g.count) FROM issue_groups g WHERE g.reportId = r.id AND g.kind = 'CRASH'
                AND (:app IS NULL OR g.packageName = :app)), 0) AS crashCount,
            COALESCE((SELECT SUM(g.count) FROM issue_groups g WHERE g.reportId = r.id AND g.kind = 'NATIVE_CRASH'
                AND (:app IS NULL OR g.packageName = :app)), 0) AS nativeCrashCount,
            COALESCE((SELECT SUM(g.count) FROM issue_groups g WHERE g.reportId = r.id AND g.kind = 'ANR'
                AND (:app IS NULL OR g.packageName = :app)), 0) AS anrCount,
            COALESCE((SELECT SUM(g.count) FROM issue_groups g WHERE g.reportId = r.id AND g.kind = 'LOW_MEMORY_KILL'
                AND (:app IS NULL OR g.packageName = :app)), 0) AS lmkCount,
            COALESCE((SELECT SUM(g.count) FROM issue_groups g WHERE g.reportId = r.id AND g.kind = 'MEMORY_LEAK'
                AND (:app IS NULL OR g.packageName = :app)), 0) AS leakCount,
            EXISTS(SELECT 1 FROM summaries s WHERE s.reportId = r.id AND s.app = COALESCE(:app, '')) AS hasSummary
        FROM reports r
        WHERE :app IS NULL OR EXISTS(
            SELECT 1 FROM issue_groups g WHERE g.reportId = r.id AND g.packageName = :app AND g.kind != 'LOW_MEMORY')
        ORDER BY r.savedAt DESC
        """,
    )
    abstract fun history(app: String?): Flow<List<ReportListItem>>

    /** Apps across all saved reports, most issues first. */
    @Query(
        """
        SELECT packageName, COUNT(DISTINCT reportId) AS reports, SUM(count) AS issues
        FROM issue_groups WHERE kind != 'LOW_MEMORY'
        GROUP BY packageName ORDER BY issues DESC, packageName
        """,
    )
    abstract fun apps(): Flow<List<AppCount>>

    /** Issues across all saved reports for export, newest report first; [app] null = all apps. */
    @Query(
        """
        SELECT * FROM app_issues WHERE :app IS NULL OR packageName = :app
        ORDER BY collectedAt DESC, reportId DESC, lastSeen DESC
        """,
    )
    abstract suspend fun appIssues(app: String?): List<AppIssueView>

    @Query("SELECT id FROM reports WHERE fingerprint = :fingerprint ORDER BY savedAt DESC LIMIT 1")
    abstract suspend fun findByFingerprint(fingerprint: String): Long?

    @Query("SELECT * FROM summaries WHERE reportId = :id AND app = :app")
    abstract suspend fun summary(id: Long, app: String): SummaryEntity?

    @Upsert
    abstract suspend fun upsertSummary(summary: SummaryEntity)

    @Query("DELETE FROM issue_groups WHERE reportId = :id")
    protected abstract suspend fun deleteGroups(id: Long)

    @Query("DELETE FROM process_stats WHERE reportId = :id")
    protected abstract suspend fun deleteStats(id: Long)

    @Query("DELETE FROM summaries WHERE reportId = :id")
    protected abstract suspend fun deleteSummaries(id: Long)

    @Query("DELETE FROM reports WHERE id = :id")
    protected abstract suspend fun deleteReport(id: Long)

    /** Children are deleted explicitly too, in case foreign keys are not enforced. */
    @Transaction
    open suspend fun delete(id: Long) {
        deleteGroups(id)
        deleteStats(id)
        deleteSummaries(id)
        deleteReport(id)
    }
}

@Database(
    entities = [ReportEntity::class, IssueGroupEntity::class, ProcessStatEntity::class, SummaryEntity::class],
    views = [AppIssueView::class],
    version = 2,
    exportSchema = false,
)
abstract class ReportDatabase : RoomDatabase() {
    abstract fun reportDao(): ReportDao
}
