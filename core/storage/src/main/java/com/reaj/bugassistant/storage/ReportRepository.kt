package com.reaj.bugassistant.storage

import android.content.Context
import androidx.room.Room
import com.reaj.bugassistant.diagnostics.DeviceReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** Saved reports on the device. Nothing here leaves the device. */
class ReportRepository internal constructor(private val dao: ReportDao) {

    /** Saved reports, newest first; with [app], only those containing that package. */
    fun history(app: String?): Flow<List<ReportListItem>> = dao.history(app?.ifBlank { null })

    /** Every app package found in saved reports, most issues first. */
    val apps: Flow<List<AppCount>> get() = dao.apps()

    /**
     * Saves [report] (always unfiltered) and returns its id. If an identical report is already saved,
     * returns that one's id so repeated scans and re-imports do not duplicate.
     */
    suspend fun save(report: DeviceReport): SaveResult {
        val full = report.forApp(null)
        dao.findByFingerprint(ReportMapper.fingerprint(full))?.let { return SaveResult(it, existing = true) }
        val rows = ReportMapper.toRows(full, savedAt = System.currentTimeMillis())
        return SaveResult(dao.insert(rows.report, rows.groups, rows.stats), existing = false)
    }

    /** Stored summary for all apps ([app] null) or one app. */
    suspend fun summary(id: Long, app: String?): String? = dao.summary(id, key(app))?.text

    suspend fun saveSummary(id: Long, app: String?, summary: String, model: String?) =
        dao.upsertSummary(SummaryEntity(id, key(app), summary, model, System.currentTimeMillis()))

    suspend fun load(id: Long): SavedReport? {
        val report = dao.report(id) ?: return null
        return ReportMapper.fromRows(report, dao.groups(id), dao.stats(id))
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /** Writes every saved issue (only [app]'s when set) as CSV to [out] and closes it; returns the number of issue rows. */
    suspend fun exportCsv(app: String?, out: OutputStream): Int {
        val rows = dao.appIssues(app?.ifBlank { null })
        withContext(Dispatchers.IO) {
            out.bufferedWriter(Charsets.UTF_8).use { IssueCsv.write(rows, it) }
        }
        return rows.size
    }

    private fun key(app: String?) = app?.trim().orEmpty()

    data class SaveResult(val id: Long, val existing: Boolean)

    companion object {
        private const val DB_NAME = "reports.db"

        @Volatile private var instance: ReportRepository? = null

        fun get(context: Context): ReportRepository = instance ?: synchronized(this) {
            instance ?: ReportRepository(
                Room.databaseBuilder(context.applicationContext, ReportDatabase::class.java, DB_NAME)
                    // Version 1 was never released; later versions must add real migrations.
                    .fallbackToDestructiveMigrationFrom(true, 1)
                    .build()
                    .reportDao(),
            ).also { instance = it }
        }
    }
}
