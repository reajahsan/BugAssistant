package com.reaj.bugassistant.storage

import com.reaj.bugassistant.diagnostics.AccessLevel
import com.reaj.bugassistant.diagnostics.DeviceHealth
import com.reaj.bugassistant.diagnostics.DeviceReport
import com.reaj.bugassistant.diagnostics.Issue
import com.reaj.bugassistant.diagnostics.IssueGroup
import com.reaj.bugassistant.diagnostics.IssueKind
import com.reaj.bugassistant.diagnostics.MemorySnapshot
import com.reaj.bugassistant.diagnostics.ProcessStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportMapperTest {

    private val crash = IssueGroup(
        signature = "CRASH|com.acme.field|NullPointerException|com.acme.Foo.bar",
        latest = Issue(
            kind = IssueKind.CRASH,
            timeMillis = 2_000,
            process = "com.acme.field",
            pid = 1234,
            type = "java.lang.NullPointerException",
            message = "Attempt to invoke virtual method",
            cause = "Caused by: x",
            frames = listOf("at com.acme.Foo.bar(Foo.kt:10)", "at android.os.Handler.dispatch(Handler.java:1)"),
        ),
        count = 3,
        firstSeenMillis = 1_000,
        lastSeenMillis = 2_000,
    )
    private val lmk = IssueGroup(
        signature = "LMK|com.other",
        latest = Issue(IssueKind.LOW_MEMORY_KILL, 1_500, "com.other", type = "lmk", freedKb = 2048),
        count = 1,
        firstSeenMillis = 1_500,
        lastSeenMillis = 1_500,
        totalFreedKb = 2048,
    )

    private val bugReport = DeviceReport(
        collectedAtMillis = 10_000,
        windowMillis = 9_000,
        device = "Acme X1 (Android 14, API 34)",
        access = AccessLevel.BUG_REPORT,
        memory = MemorySnapshot(totalBytes = 4_000_000_000, availableBytes = 500_000_000),
        groups = listOf(crash, lmk),
        notes = listOf("one", "two"),
        sourceName = "bugreport-x1.zip",
        health = DeviceHealth(
            uptime = "up 3 days, 2:10",
            cpuLoad = listOf(7.5, 6.0, 5.25),
            cpuTotalPercent = 92.5,
            topCpu = listOf(ProcessStat("com.acme.field", 40.0), ProcessStat("system_server", 20.0)),
            topMemory = listOf(ProcessStat("com.acme.field", 512_000.0)),
            storageFreeBytes = 1_000,
            storageTotalBytes = 64_000,
            thermalStatus = 3,
            jank = listOf(ProcessStat("com.acme.field", 300.0, events = 5)),
        ),
    )

    private fun roundTrip(report: DeviceReport): SavedReport {
        val rows = ReportMapper.toRows(report, savedAt = 42)
        return ReportMapper.fromRows(rows.report.copy(id = 7), rows.groups, rows.stats.shuffled())
    }

    @Test
    fun `bug report round-trips`() {
        val saved = roundTrip(bugReport)
        assertEquals(bugReport, saved.report)
        assertEquals(7L, saved.id)
        assertEquals(42L, saved.savedAtMillis)
    }

    @Test
    fun `groups store the app package without the process suffix`() {
        val sync = crash.copy(signature = "sync", latest = crash.latest.copy(process = "com.acme.field:sync"))
        val rows = ReportMapper.toRows(bugReport.copy(groups = listOf(sync, lmk)), 0)
        assertEquals(listOf("com.acme.field", "com.other"), rows.groups.map { it.packageName })
        assertEquals("com.acme.field:sync", rows.groups[0].process)
    }

    @Test
    fun `forApp keeps the app's issues and device-wide low memory`() {
        val lowMem = IssueGroup("lowmem", Issue(IssueKind.LOW_MEMORY, 1, "system", type = "lowmem"), 2, 1, 1)
        val sync = crash.copy(signature = "sync", latest = crash.latest.copy(process = "com.acme.field:sync"))
        val report = bugReport.copy(groups = listOf(crash, sync, lmk, lowMem))
        val filtered = report.forApp("com.acme.field")
        assertEquals(listOf(crash, sync, lowMem), filtered.groups)
        assertEquals("com.acme.field", filtered.appFilter)
        assertEquals(report.health, filtered.health)
        assertEquals(listOf("com.acme.field", "com.other"), report.apps)
        assertEquals(report.copy(appFilter = null), filtered.forApp(null).copy(groups = report.groups))
    }

    @Test
    fun `live scan without health or memory round-trips`() {
        val live = bugReport.copy(access = AccessLevel.FULL, sourceName = null, health = null, memory = null, notes = emptyList())
        val saved = roundTrip(live)
        assertEquals(live, saved.report)
        assertNull(saved.report.health)
    }

    @Test
    fun `health with only process lists survives empty embedded columns`() {
        val report = bugReport.copy(health = DeviceHealth(jank = listOf(ProcessStat("a", 1.0, 1))))
        val rows = ReportMapper.toRows(report, 0)
        // Room yields a null embedded object when every column is null.
        val saved = ReportMapper.fromRows(rows.report.copy(health = null), rows.groups, rows.stats)
        assertEquals(report.health, saved.report.health)
    }

    @Test
    fun `counts are denormalized`() {
        val rows = ReportMapper.toRows(bugReport, 0)
        assertEquals(3, rows.report.crashCount)
        assertEquals(1, rows.report.lmkCount)
        assertEquals(0, rows.report.anrCount)
    }

    @Test
    fun `fingerprint is stable and sensitive to content`() {
        assertEquals(ReportMapper.fingerprint(bugReport), ReportMapper.fingerprint(bugReport.copy(notes = emptyList())))
        assertNotEquals(ReportMapper.fingerprint(bugReport), ReportMapper.fingerprint(bugReport.copy(groups = listOf(crash))))
        assertNotEquals(ReportMapper.fingerprint(bugReport), ReportMapper.fingerprint(bugReport.copy(sourceName = "other.zip")))
    }
}
