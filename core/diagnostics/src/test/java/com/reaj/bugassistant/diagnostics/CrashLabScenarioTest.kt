package com.reaj.bugassistant.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bug report as it looks after using every button in the Crash Lab test app (samples/crashlab).
 * Each crash restarts the app, so each fault has its own PID. PID 9500 is still running with leaked
 * Activities and a 90 % full heap when the report is taken.
 */
class CrashLabScenarioTest {
    private val app = "com.reaj.crashlab"

    // Android 16 am_crash puts the PID first; for native crashes it is system_server's PID (1500), not the app's.
    private val main = """
        ========================================================
        == dumpstate: 2026-11-12 11:00:00
        ========================================================

        Build fingerprint: 'google/shiba/shiba:15/AP4A/123:user/release-keys'
        Bugreport format version: 2.0
        ------ SYSTEM LOG (logcat -v threadtime -v printable -v uid -d *:v) ------
        --------- beginning of main
        11-12 10:00:00.000  1000  1500  1600 I ActivityManager: Start proc 9000:com.reaj.crashlab/u0a301 for activity {com.reaj.crashlab/.MainActivity}
        11-12 10:20:00.000 10301  9200  9200 I crashlab: Clamp target GC heap from 259MB to 256MB
        11-12 10:20:01.000 10301  9200  9200 W crashlab: Throwing OutOfMemoryError "Failed to allocate a 1048592 byte allocation with 524280 free bytes and 511KB until OOM, target footprint 268435456, growth limit 268435456" (VmSize 17000000 kB)
        11-12 10:40:00.000  1000  1500  1600 I ActivityManager: Start proc 9500:com.reaj.crashlab/u0a301 for activity {com.reaj.crashlab/.MainActivity}
        --------- beginning of crash
        11-12 10:05:00.000 10301  9000  9000 E AndroidRuntime: FATAL EXCEPTION: main
        11-12 10:05:00.000 10301  9000  9000 E AndroidRuntime: Process: com.reaj.crashlab, PID: 9000
        11-12 10:05:00.000 10301  9000  9000 E AndroidRuntime: java.lang.IllegalStateException: CrashLab: deliberate crash on the main thread
        11-12 10:05:00.000 10301  9000  9000 E AndroidRuntime: 	at com.reaj.crashlab.Faults.javaCrash(Faults.kt:18)
        11-12 10:05:00.000 10301  9000  9000 E AndroidRuntime: 	at com.reaj.crashlab.MainActivity.onCreate${'$'}lambda${'$'}4(MainActivity.kt:37)
        11-12 10:10:00.000 10301  9100  9150 E AndroidRuntime: FATAL EXCEPTION: crashlab-worker
        11-12 10:10:00.000 10301  9100  9150 E AndroidRuntime: Process: com.reaj.crashlab, PID: 9100
        11-12 10:10:00.000 10301  9100  9150 E AndroidRuntime: java.lang.IllegalArgumentException: CrashLab: deliberate crash on a worker thread
        11-12 10:10:00.000 10301  9100  9150 E AndroidRuntime: 	at com.reaj.crashlab.Faults.backgroundCrash${'$'}lambda${'$'}0(Faults.kt:22)
        11-12 10:10:00.000 10301  9100  9150 E AndroidRuntime: 	at java.lang.Thread.run(Thread.java:1012)
        11-12 10:20:01.100 10301  9200  9200 E AndroidRuntime: FATAL EXCEPTION: main
        11-12 10:20:01.100 10301  9200  9200 E AndroidRuntime: Process: com.reaj.crashlab, PID: 9200
        11-12 10:20:01.100 10301  9200  9200 E AndroidRuntime: java.lang.OutOfMemoryError: Failed to allocate a 1048592 byte allocation with 524280 free bytes and 511KB until OOM
        11-12 10:20:01.100 10301  9200  9200 E AndroidRuntime: 	at com.reaj.crashlab.Faults.outOfMemory(Faults.kt:52)
        11-12 10:20:01.100 10301  9200  9200 E AndroidRuntime: 	at com.reaj.crashlab.MainActivity.onCreate${'$'}lambda${'$'}3(MainActivity.kt:36)
        ------ 1.200s was the duration of 'SYSTEM LOG' ------
        ------ EVENT LOG (logcat -b events -v threadtime -v printable -v uid -d *:v) ------
        11-12 10:05:00.100  1000  1500  1700 I am_crash: [9000,0,com.reaj.crashlab,952745540,java.lang.IllegalStateException,CrashLab: deliberate crash on the main thread,Faults.kt,18,0]
        11-12 10:30:00.500  1000  1500  9999 I am_crash: [1500,0,com.reaj.crashlab,952745540,Native crash,Segmentation fault,unknown,0,0]
        11-12 10:35:00.000  1000  1500  1700 I am_anr  : [0,9400,com.reaj.crashlab,952745540,Input dispatching timed out (Waiting to send non-key event because the touched window has not finished processing)]
        ------ 0.300s was the duration of 'EVENT LOG' ------
        ------ SYSTEM PROPERTIES (getprop) ------
        [persist.sys.timezone]: [UTC]
        [dalvik.vm.heapgrowthlimit]: [256m]
        [dalvik.vm.heapsize]: [512m]
        [ro.build.version.release]: [15]
        [ro.build.version.sdk]: [35]
        [ro.product.manufacturer]: [Google]
        [ro.product.model]: [Pixel 8]
        ------ 0.010s was the duration of 'SYSTEM PROPERTIES' ------
        ------ DUMPSYS CRITICAL (/system/bin/dumpsys -t 100 --priority CRITICAL) ------
        ------ 0.300s was the duration of 'DUMPSYS CRITICAL' ------
        ------ DUMPSYS HIGH (/system/bin/dumpsys -t 100 --priority HIGH) ------
        -------------------------------------------------------------------------------
        DUMP OF SERVICE HIGH meminfo:
        Applications Memory Usage (in Kilobytes):
        Uptime: 266000000 Realtime: 266000000

        ** MEMINFO in pid 9500 [com.reaj.crashlab] **
                           Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap
                         Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free
                        ------   ------   ------   ------   ------   ------   ------   ------
          Native Heap    12000    11900        0        0    14000    20000    15000     5000
          Dalvik Heap   236000   235900        0        0   238000   245000   236000     9000
                TOTAL   300000   290000     1000        0   320000   265000   251000    14000

         Objects
                       Views:     2042         ViewRootImpl:        1
                 AppContexts:       12           Activities:       11
                      Assets:       14        AssetManagers:        0

        Total PSS by process:
            300,000K: com.reaj.crashlab (pid 9500 / activities)
        --------- 0.200s was the duration of dumpsys meminfo, ending at: 2026-11-12 11:00:02
        ------ 0.300s was the duration of 'DUMPSYS HIGH' ------
    """.trimIndent()

    // Os.kill(getpid(), SIGSEGV) from the app: a user-sent signal, so code 0 (SI_USER) and no fault address.
    private val tombstone = """
        *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
        Build fingerprint: 'google/shiba/shiba:15/AP4A/123:user/release-keys'
        Timestamp: 2026-11-12 10:30:00.000000000+0000
        pid: 9300, tid: 9300, name: com.reaj.crashlab  >>> com.reaj.crashlab <<<
        signal 11 (SIGSEGV), code 0 (SI_USER from pid 9300, uid 10301), fault addr --------
        backtrace:
              #00 pc 00000000000a1b2c  /apex/com.android.runtime/lib64/bionic/libc.so (kill+8) (BuildId: abc)
              #01 pc 0000000000123456  /apex/com.android.art/lib64/libjavacore.so (Linux_kill(_JNIEnv*, _jobject*, int, int)+24) (BuildId: def)
    """.trimIndent()

    private val anr = """
        Subject: Input dispatching timed out (Waiting to send non-key event because the touched window has not finished processing)

        ----- pid 9400 at 2026-11-12 10:35:00.000 -----
        Cmd line: com.reaj.crashlab

        "main" prio=5 tid=1 Sleeping
          | group="main" sCount=1
          at java.lang.Thread.sleep(Native method)
          at com.reaj.crashlab.Faults.freezeMainThread(Faults.kt:31)
          at com.reaj.crashlab.MainActivity.onCreate${'$'}lambda${'$'}7(MainActivity.kt:40)

    """.trimIndent()

    private fun parse(): DeviceReport {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            listOf(
                "FS/data/tombstones/tombstone_03" to tombstone,
                "FS/data/anr/anr_2026-11-12-10-35-00-000" to anr,
                "bugreport-shiba-AP4A-2026-11-12-11-00-00.txt" to main,
            ).forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return BugReportParser.parse(ByteArrayInputStream(out.toByteArray()), "bugreport.zip", ZoneOffset.UTC)
    }

    private fun DeviceReport.types(kind: IssueKind) = groups.filter { it.kind == kind }.map { it.latest.type }.toSet()

    @Test
    fun `main thread, worker thread and out-of-memory crashes are separate crash groups`() {
        val r = parse().forApp(app)
        assertEquals(
            setOf(
                "java.lang.IllegalStateException",
                "java.lang.IllegalArgumentException",
                "java.lang.OutOfMemoryError",
            ),
            r.types(IssueKind.CRASH),
        )
        // logcat + am_crash for the same crash collapse to one.
        assertEquals(3, r.count(IssueKind.CRASH))
        val worker = r.groups.single { it.latest.type == "java.lang.IllegalArgumentException" }
        assertTrue(worker.latest.frames.any { it.contains("Faults.backgroundCrash") })
    }

    @Test
    fun `self-sent SIGSEGV is a native crash`() {
        val native = parse().forApp(app).groups.single { it.kind == IssueKind.NATIVE_CRASH }
        assertEquals(9300, native.latest.pid)
        assertTrue(native.latest.type, native.latest.type.contains("SIGSEGV"))
        assertEquals(1, native.count)
    }

    @Test
    fun `frozen main thread is an ANR with the sleeping frame`() {
        val anr = parse().forApp(app).groups.single { it.kind == IssueKind.ANR }
        assertEquals(1, anr.count)
        assertTrue(anr.latest.frames.toString(), anr.latest.frames.any { it.contains("Faults.freezeMainThread") })
    }

    @Test
    fun `leaked activities, full heap and OOM are memory leak suspects`() {
        val r = parse().forApp(app)
        assertEquals(
            setOf(
                "OutOfMemoryError thrown",
                "Java heap at its limit",
                "Java heap near its limit",
                "Many Activity objects in memory",
            ),
            r.types(IssueKind.MEMORY_LEAK),
        )
        val activities = r.groups.single { it.latest.type == "Many Activity objects in memory" }
        assertEquals("11 Activities in memory, 12 AppContexts, 2042 Views", activities.latest.message)

        val digest = ReportDigest.build(r)
        assertTrue(digest, digest.contains("Memory leak suspect in $app"))
    }

    @Test
    fun `everything is attributed to the app`() {
        val r = parse()
        assertEquals(listOf(app), r.apps)
        assertEquals(r.groups.size, r.forApp(app).groups.size)
    }
}
