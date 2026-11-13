package com.reaj.bugassistant.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BugReportParserTest {

    private val main = """
        ========================================================
        == dumpstate: 2026-11-12 10:00:00
        ========================================================

        Build: AP4A.250105.002
        Build fingerprint: 'google/husky/husky:15/AP4A/123:user/release-keys'
        Uptime: up 0 weeks, 3 days, 2 hours, 10 minutes,  load average: 9.50, 8.25, 7.00
        Bugreport format version: 2.0
        ------ MEMORY INFO (/proc/meminfo) ------
        MemTotal:        7800000 kB
        MemFree:          100000 kB
        MemAvailable:     400000 kB
        SwapTotal:       4000000 kB
        SwapFree:         500000 kB
        ------ 0.003s was the duration of 'MEMORY INFO' ------
        ------ SYSTEM LOG (logcat -v threadtime -v printable -v uid -d *:v) ------
        --------- beginning of main
        11-12 09:00:00.000  1000  1500  1600 I ActivityManager: Start proc 4321:com.example.field/u0a234 for activity {com.example.field/.Main}
        11-12 09:05:00.000 10234  4321  4321 I Choreographer: Skipped 120 frames!  The application may be doing too much work on its main thread.
        11-12 09:06:00.000 10234  4321  4321 I Choreographer: Skipped 80 frames!  The application may be doing too much work on its main thread.
        11-12 09:06:01.000 10234  4321  4400 I OpenGLRenderer: Davey! duration=1400ms; Flags=0
        11-12 09:30:00.000  1000   900   900 I lowmemorykiller: Kill 'com.example.news' (2222), uid 10111, oom_score_adj 905 to free 120000kB rss, 30000kB swap; reason: low watermark is breached
        11-12 09:31:00.000  1000   900   900 I lowmemorykiller: Kill 'com.example.news' (2223), uid 10111, oom_score_adj 905 to free 100000kB rss, 30000kB swap
        11-12 09:40:00.000  1000  1500  1600 I ActivityManager: Some unrelated line
        11-12 09:44:00.000 10234  4321  4325 I art     : Clamp target GC heap from 263MB to 256MB
        11-12 09:45:00.000 10234  4321  4330 W com.example.field: Throwing OutOfMemoryError "Failed to allocate a 1048592 byte allocation with 200 free bytes and 0B until OOM, target footprint 268435456, growth limit 268435456"
        11-12 09:46:00.000 10234  4321  4325 I art     : Clamp target GC heap from 262MB to 256MB
        --------- beginning of crash
        11-12 09:50:00.000 10234  4321  4321 E AndroidRuntime: FATAL EXCEPTION: main
        11-12 09:50:00.000 10234  4321  4321 E AndroidRuntime: Process: com.example.field, PID: 4321
        11-12 09:50:00.000 10234  4321  4321 E AndroidRuntime: java.lang.IllegalStateException: Form not loaded
        11-12 09:50:00.000 10234  4321  4321 E AndroidRuntime: 	at com.example.field.FormActivity.submit(FormActivity.kt:88)
        11-12 09:50:00.000 10234  4321  4321 E AndroidRuntime: 	at android.view.View.performClick(View.java:7500)
        ------ 1.200s was the duration of 'SYSTEM LOG' ------
        ------ EVENT LOG (logcat -b events -v threadtime -v printable -v uid -d *:v) ------
        11-12 09:50:00.100  1000  1500  1700 I am_crash: [0,4321,com.example.field,952745540,java.lang.IllegalStateException,Form not loaded,FormActivity.kt,88,0]
        11-12 09:55:00.000  1000  1500  1700 I am_anr  : [0,5555,com.example.mail,952745540,Input dispatching timed out (Waiting to send key event)]
        11-12 09:56:00.000  1000  1500  1700 I am_crash: [0,7777,com.example.camera,952745540,Native crash,Abort,unknown,0,0]
        ------ 0.300s was the duration of 'EVENT LOG' ------
        ------ SYSTEM PROPERTIES (getprop) ------
        [persist.sys.timezone]: [America/New_York]
        [dalvik.vm.heapgrowthlimit]: [256m]
        [dalvik.vm.heapsize]: [512m]
        [ro.build.version.release]: [15]
        [ro.build.version.sdk]: [35]
        [ro.product.manufacturer]: [Google]
        [ro.product.model]: [Pixel 8 Pro]
        ------ 0.010s was the duration of 'SYSTEM PROPERTIES' ------
        ------ DROPBOX SYSTEM APP CRASHES (dumpsys dropbox -p system_app_crash) ------
        Drop box contents: 1 entries
        ========================================
        2026-11-12 08:00:00 system_app_crash (text, 400 bytes)
        Process: com.android.systemui
        PID: 1800

        java.lang.NullPointerException: Attempt to read from null array
        	at com.android.systemui.qs.TileLayout.update(TileLayout.java:120)

        ------ 0.050s was the duration of 'DROPBOX SYSTEM APP CRASHES' ------
        ------ DUMPSYS CRITICAL (/system/bin/dumpsys -t 100 --priority CRITICAL) ------
        -------------------------------------------------------------------------------
        DUMP OF SERVICE CRITICAL cpuinfo:
        Load: 12.04 / 11.65 / 11.39
        CPU usage from 50000ms to 10000ms ago (2026-11-12 09:59:00.000 to 2026-11-12 09:59:40.000):
          45% 1500/system_server: 30% user + 15% kernel / faults: 1000 minor
          38% 4321/com.example.field: 35% user + 3% kernel
        +0% 9999/new.process: 0% user + 0% kernel
        92% TOTAL: 60% user + 30% kernel + 2% iowait
        --------- 0.050s was the duration of dumpsys cpuinfo, ending at: 2026-11-12 10:00:01
        DUMP OF SERVICE HIGH meminfo:
        Applications Memory Usage (in Kilobytes):
        Uptime: 266000000 Realtime: 266000000

        ** MEMINFO in pid 4321 [com.example.field] **
                           Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap
                         Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free
                        ------   ------   ------   ------   ------   ------   ------   ------
          Native Heap    30404    30364        0       47    32620    42156    33426     4605
          Dalvik Heap   241794   241756        0       49   244268   262000   240000    22000
                TOTAL   612345   600000     1000      100   650000   304156   273426    26605

         App Summary
                               Pss(KB)                        Rss(KB)
                                ------                         ------
                   Java Heap:   241756                         244268

         Objects
                       Views:     4210         ViewRootImpl:        1
                 AppContexts:       11           Activities:        9
                      Assets:       16        AssetManagers:        0

        ** MEMINFO in pid 1500 [system_server] **
                           Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap
                         Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free
          Dalvik Heap    40000    39000        0       49    42000    65536    30000    35536

         Objects
                       Views:        0         ViewRootImpl:        0
                 AppContexts:        3           Activities:        0

        Total PSS by process:
            612,345K: com.example.field (pid 4321 / activities)
            401,000K: system_server (pid 1500)

        Total PSS by OOM adjustment:
            999,999K: Native
        --------- 0.200s was the duration of dumpsys meminfo, ending at: 2026-11-12 10:00:02
        ------ 0.300s was the duration of 'DUMPSYS CRITICAL' ------
        ------ DUMPSYS (/system/bin/dumpsys) ------
        DUMP OF SERVICE diskstats:
        Latency: 2ms [512B Data Write]
        Data-Free: 2000000K / 100000000K total = 2% free
        --------- 0.010s was the duration of dumpsys diskstats, ending at: 2026-11-12 10:00:03
        DUMP OF SERVICE thermalservice:
        IsStatusOverride: false
        Thermal Status: 3
        --------- 0.010s was the duration of dumpsys thermalservice, ending at: 2026-11-12 10:00:04
        ------ 5.000s was the duration of 'DUMPSYS' ------
        ------ VM TRACES JUST NOW (/data/anr/dumptrace_abc: 2026-11-12 10:00:05) ------
        ----- pid 1500 at 2026-11-12 10:00:05 -----
        Cmd line: system_server
        "main" prio=5 tid=1 Native
          at android.os.MessageQueue.nativePollOnce(Native method)

        ------ 1.000s was the duration of 'VM TRACES JUST NOW' ------
    """.trimIndent()

    private val tombstone = """
        *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
        Build fingerprint: 'google/husky/husky:15/AP4A/123:user/release-keys'
        Timestamp: 2026-11-12 09:56:00.123456789-0500
        pid: 7777, tid: 7790, name: CameraThread  >>> com.example.camera <<<
        signal 6 (SIGABRT), code -1 (SI_QUEUE), fault addr --------
        Abort message: 'Check failed: buffer != nullptr'
        backtrace:
              #00 pc 000000000005b4e4  /apex/com.android.runtime/lib64/bionic/libc.so (abort+164) (BuildId: abc)
              #01 pc 0000000000012345  /data/app/com.example.camera/lib/arm64/libcam.so (Pipeline::push()+40) (BuildId: def)
    """.trimIndent()

    private val anr = """
        Subject: Input dispatching timed out (Waiting to send key event because the focused window has not finished processing)

        ----- pid 5555 at 2026-11-12 09:55:01.000 -----
        Cmd line: com.example.mail

        "main" prio=5 tid=1 Blocked
          | group="main" sCount=1
          at com.example.mail.db.Store.read(Store.kt:88)
          - waiting to lock <0x0a1b2c3d> (a java.lang.Object) held by thread 23
          at com.example.mail.Inbox.onResume(Inbox.kt:40)

    """.trimIndent()

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun parseZip() = BugReportParser.parse(
        ByteArrayInputStream(
            zip(
                "version.txt" to "2.0",
                "FS/data/tombstones/tombstone_00" to tombstone,
                "FS/data/tombstones/tombstone_00.pb" to "binary",
                "FS/data/anr/anr_2026-11-12-09-55-01-000" to anr,
                "bugreport-husky-AP4A-2026-11-12-10-00-00.txt" to main,
            ),
        ),
        fileName = "bugreport.zip",
        defaultZone = ZoneOffset.UTC,
    )

    private val ny = ZoneId.of("America/New_York")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(ny).toInstant().toEpochMilli()

    @Test
    fun `reads device, time and memory`() {
        val r = parseZip()
        assertEquals(AccessLevel.BUG_REPORT, r.access)
        assertEquals("bugreport.zip", r.sourceName)
        assertEquals("Google Pixel 8 Pro, Android 15 (API 35)", r.device)
        // The device time zone from getprop wins over the default.
        assertEquals(at("2026-11-12T10:00:00"), r.collectedAtMillis)
        val m = assertNotNullAndGet(r.memory)
        assertEquals(7_800_000L * 1024, m.totalBytes)
        assertEquals(400_000L * 1024, m.availableBytes)
        assertEquals(3_500_000L * 1024, m.swapUsedBytes)
    }

    @Test
    fun `finds crashes, ANRs, native crashes and kills from every source`() {
        val r = parseZip()
        // The logcat crash and the am_crash event are one crash (same pid, same minute).
        assertEquals(2, r.count(IssueKind.CRASH))
        val field = r.groups.single { it.process == "com.example.field" && it.kind == IssueKind.CRASH }
        assertEquals("java.lang.IllegalStateException", field.latest.type)
        assertEquals("com.example.field.FormActivity.submit(FormActivity.kt:88)", field.latest.frames.first())
        assertEquals(at("2026-11-12T09:50:00"), field.lastSeenMillis)

        assertTrue(r.groups.any { it.process == "com.android.systemui" && it.kind == IssueKind.CRASH })

        // The tombstone file and the am_crash "Native crash" event are one native crash.
        val native = r.groups.single { it.kind == IssueKind.NATIVE_CRASH }
        assertEquals(1, native.count)
        assertEquals("com.example.camera", native.process)
        assertEquals("SIGABRT (SI_QUEUE)", native.latest.type)
        assertTrue(native.latest.frames.contains("libcam.so (Pipeline::push())"))

        // am_anr event + ANR file: one ANR, with the stack from the file.
        val anrGroup = r.groups.single { it.kind == IssueKind.ANR }
        assertEquals(1, anrGroup.count)
        assertEquals("com.example.mail", anrGroup.process)
        assertEquals("Input dispatching timed out", anrGroup.latest.type)
        assertEquals("com.example.mail.db.Store.read(Store.kt:88)", anrGroup.latest.frames.first())

        val lmk = r.groups.single { it.kind == IssueKind.LOW_MEMORY_KILL }
        assertEquals(2, lmk.count)
        assertEquals(220_000L, lmk.totalFreedKb)
    }

    @Test
    fun `finds memory leak suspects`() {
        val r = parseZip()
        val leaks = r.groups.filter { it.kind == IssueKind.MEMORY_LEAK }
        assertTrue(leaks.all { it.process == "com.example.field" })

        val atLimit = leaks.single { it.latest.type == "Java heap at its limit" }
        assertEquals(2, atLimit.count)
        assertEquals(at("2026-11-12T09:46:00"), atLimit.lastSeenMillis)

        val oom = leaks.single { it.latest.type == "OutOfMemoryError thrown" }
        assertEquals(4321, oom.latest.pid)
        assertTrue(oom.latest.message!!.contains("Failed to allocate a 1048592 byte allocation"))

        // 240,000 kB of a 256 MB (262,144 kB) growth limit = 91%.
        val nearLimit = leaks.single { it.latest.type == "Java heap near its limit" }
        assertEquals("Java heap 234 MB used of 256 MB limit (91%)", nearLimit.latest.message)
        assertEquals(r.collectedAtMillis, nearLimit.lastSeenMillis)

        val activities = leaks.single { it.latest.type == "Many Activity objects in memory" }
        assertEquals("9 Activities in memory, 11 AppContexts, 4210 Views", activities.latest.message)

        // system_server: small heap, no Activities.
        assertEquals(4, leaks.size)
        assertEquals(5, r.count(IssueKind.MEMORY_LEAK))

        // Leak signals follow the app filter and reach the model digest.
        assertEquals(4, r.forApp("com.example.field").groups.count { it.kind == IssueKind.MEMORY_LEAK })
        assertTrue(r.forApp("com.example.mail").groups.none { it.kind == IssueKind.MEMORY_LEAK })
        val digest = ReportDigest.build(r.forApp("com.example.field"))
        assertTrue(digest, digest.contains("Memory leak suspect in com.example.field"))
        assertTrue(digest, digest.contains("5 memory-leak signals"))
    }

    @Test
    fun `routine stack dumps are not ANRs`() {
        val r = parseZip()
        assertTrue(r.groups.none { it.kind == IssueKind.ANR && it.process == "system_server" })
    }

    @Test
    fun `reads slowness signals`() {
        val h = assertNotNullAndGet(parseZip().health)
        assertEquals("up 0 weeks, 3 days, 2 hours, 10 minutes", h.uptime)
        assertEquals(listOf(9.5, 8.25, 7.0), h.cpuLoad)
        assertEquals(92.0, h.cpuTotalPercent!!, 0.0)
        assertEquals(listOf("system_server", "com.example.field"), h.topCpu.map { it.process })
        assertEquals(ProcessStat("com.example.field", 612_345.0), h.topMemory.first())
        assertEquals(2, h.topMemory.size)
        assertEquals(2_000_000L * 1024, h.storageFreeBytes)
        assertEquals(2, h.storageFreePercent)
        assertEquals(3, h.thermalStatus)
        // pid 4321 is named from "Start proc"; two Choreographer reports plus one Davey.
        assertEquals(ProcessStat("com.example.field", 200.0, 3), h.jank.single())
    }

    @Test
    fun `plain text bug report works without the zip`() {
        val r = BugReportParser.parse(ByteArrayInputStream(main.toByteArray()), "bugreport.txt", ZoneOffset.UTC)
        assertEquals("Google Pixel 8 Pro, Android 15 (API 35)", r.device)
        assertTrue(r.count(IssueKind.CRASH) >= 1)
        // Without the tombstone file, the am_crash event still reports the native crash.
        assertEquals("com.example.camera", r.groups.single { it.kind == IssueKind.NATIVE_CRASH }.process)
    }

    @Test(expected = BugReportParser.NotABugReportException::class)
    fun `rejects zips without a bug report`() {
        BugReportParser.parse(ByteArrayInputStream(zip("photo.jpg" to "x")), "photos.zip")
    }

    @Test(expected = BugReportParser.NotABugReportException::class)
    fun `rejects the small dumpstate status txt shown next to the zip`() {
        val status = "Starting dumpstate\nBegin:/bugreports/dumpstate-2026-11-12.zip\nprogress: 100\n"
        BugReportParser.parse(ByteArrayInputStream(status.toByteArray()), "dumpstate-2026-11-12-12-43-38.txt")
    }

    @Test
    fun `old tombstones do not stretch the window`() {
        val old = tombstone.replace("2026-11-12 09:56:00", "2024-05-28 15:31:29").replace("7777", "8888")
            .replace("com.example.camera", "com.example.old")
        val r = BugReportParser.parse(
            ByteArrayInputStream(
                zip(
                    "FS/data/tombstones/tombstone_00" to tombstone,
                    "FS/data/tombstones/tombstone_01" to old,
                    "bugreport-husky-AP4A-2026-11-12-10-00-00.txt" to main,
                ),
            ),
            fileName = "bugreport.zip",
            defaultZone = ZoneOffset.UTC,
        )
        // Logs start at 09:00 and the report was taken at 10:00.
        assertEquals(3_600_000L, r.windowMillis)
        assertTrue(r.groups.any { it.process == "com.example.old" })
        assertTrue(r.notes.any { "predate the logs" in it && "2024-05-28" in it })
    }

    @Test
    fun `digest describes slowness`() {
        val digest = ReportDigest.build(parseZip())
        assertTrue(digest, digest.contains("Source: imported bug report"))
        assertTrue(digest, digest.contains("Thermal status: severe"))
        assertTrue(digest, digest.contains("Storage: 1.9 GB free of 95.4 GB (2%) (low)"))
        assertTrue(digest, digest.contains("UI jank"))
        assertTrue(digest, digest.contains("com.example.field 597 MB"))
        assertTrue(digest.length <= ReportDigest.DEFAULT_MAX_CHARS)
    }

    @Test
    fun `logcat with uid column parses`() {
        val l = LogcatParser.parseLine(
            "11-12 09:50:00.000 u0_a234  4321  4400 E AndroidRuntime: FATAL EXCEPTION: main",
            nowMillis = 1_800_000_000_000L, zone = ZoneOffset.UTC,
        )
        assertEquals(4321, l?.pid)
        assertEquals("AndroidRuntime", l?.tag)
    }

    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
