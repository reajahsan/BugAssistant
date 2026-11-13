package com.reaj.bugassistant.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ParsersTest {

    private val javaCrash = """
        Process: com.example.shop
        PID: 4321
        UID: 10234
        Package: com.example.shop v42 (4.2.0)
        Foreground: Yes
        Build: google/husky/husky:15/AP4A/123:user/release-keys

        java.lang.RuntimeException: Unable to start activity ComponentInfo{com.example.shop/com.example.shop.CartActivity}
        	at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:3782)
        	at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:3922)
        Caused by: java.lang.NullPointerException: Attempt to invoke virtual method 'int java.util.List.size()' on a null object reference
        	at com.example.shop.CartActivity.onCreate(CartActivity.kt:57)
    """.trimIndent()

    private val nativeCrash = """
        *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
        Build fingerprint: 'google/husky/husky:15/AP4A/123:user/release-keys'
        ABI: 'arm64'
        pid: 9876, tid: 9901, name: RenderThread  >>> com.example.game <<<
        signal 6 (SIGABRT), code -1 (SI_QUEUE), fault addr --------
        Abort message: 'texture pool exhausted'
        backtrace:
              #00 pc 000000000005c1a0  /apex/com.android.runtime/lib64/bionic/libc.so (abort+164) (BuildId: abc)
              #01 pc 00000000000a1b2c  /data/app/~~x/com.example.game/lib/arm64/libgame.so (TexturePool::alloc(int)+88)
              #02 pc 00000000000a0000  /data/app/~~x/com.example.game/lib/arm64/libgame.so (BuildId: def)
        backtrace:
              #00 pc 0000000000011111  /apex/com.android.runtime/lib64/bionic/libc.so (__epoll_pwait+8)
    """.trimIndent()

    private val anr = """
        Process: com.example.mail
        PID: 2222
        Subject: Input dispatching timed out (8c2 com.example.mail/com.example.mail.Inbox is not responding. Waited 5001ms)
        Build: google/husky

        CPU usage from 0ms to 5000ms later:
          90% 2222/com.example.mail: 80% user + 10% kernel

        ----- pid 2222 at 2026-11-12 09:00:00 -----
        Cmd line: com.example.mail

        "main" prio=5 tid=1 Blocked
          | group="main" sCount=1
          at com.example.mail.db.Store.read(Store.kt:88)
          - waiting to lock <0x0a1b2c3d> (a java.lang.Object) held by thread 23
          at com.example.mail.Inbox.onResume(Inbox.kt:40)
          at android.app.Activity.performResume(Activity.java:8000)

        "Signal Catcher" daemon prio=10 tid=2 Runnable
          at dalvik.system.Other.frame(Other.java:1)
    """.trimIndent()

    @Test fun mapsDropBoxTags() {
        assertEquals(IssueKind.CRASH, CrashParser.kindForDropBoxTag("data_app_crash"))
        assertEquals(IssueKind.CRASH, CrashParser.kindForDropBoxTag("system_server_crash"))
        assertEquals(IssueKind.NATIVE_CRASH, CrashParser.kindForDropBoxTag("data_app_native_crash"))
        assertEquals(IssueKind.NATIVE_CRASH, CrashParser.kindForDropBoxTag("SYSTEM_TOMBSTONE"))
        assertEquals(IssueKind.ANR, CrashParser.kindForDropBoxTag("system_app_anr"))
        assertEquals(IssueKind.ANR, CrashParser.kindForDropBoxTag("system_server_watchdog"))
        assertEquals(IssueKind.LOW_MEMORY, CrashParser.kindForDropBoxTag("system_server_lowmem"))
        assertNull(CrashParser.kindForDropBoxTag("SYSTEM_BOOT"))
    }

    @Test fun parsesJavaCrash() {
        val i = CrashParser.parseJavaCrash(1L, javaCrash)
        assertEquals("com.example.shop", i.process)
        assertEquals(4321, i.pid)
        assertEquals("java.lang.RuntimeException", i.type)
        assertTrue(i.cause!!.startsWith("java.lang.NullPointerException"))
        assertEquals(listOf("com.example.shop.CartActivity.onCreate(CartActivity.kt:57)"), i.frames)   // root cause frames
    }

    @Test fun parsesNativeCrashOnlyCrashingThread() {
        val i = CrashParser.parseNativeCrash(1L, nativeCrash)
        assertEquals("com.example.game", i.process)
        assertEquals(9876, i.pid)
        assertEquals("SIGABRT (SI_QUEUE)", i.type)
        assertEquals("texture pool exhausted", i.message)
        assertEquals(listOf("libc.so (abort)", "libgame.so (TexturePool::alloc(int))", "libgame.so"), i.frames)
    }

    @Test fun parsesAnrMainThread() {
        val i = CrashParser.parseAnr(1L, anr)
        assertEquals("com.example.mail", i.process)
        assertEquals("Input dispatching timed out", i.type)
        assertTrue(i.message!!.endsWith("main thread: Blocked"))
        assertEquals("waiting to lock <0x0a1b2c3d> (a java.lang.Object) held by thread 23", i.cause)
        assertEquals(3, i.frames.size)
        assertEquals("com.example.mail.db.Store.read(Store.kt:88)", i.frames.first())
    }

    @Test fun parsesLogcatCrashAnrAndLmk() {
        val log = """
            --------- beginning of crash
            1762956000.100  4321  4321 E AndroidRuntime: FATAL EXCEPTION: main
            1762956000.100  4321  4321 E AndroidRuntime: Process: com.example.shop, PID: 4321
            1762956000.100  4321  4321 E AndroidRuntime: java.lang.IllegalStateException: boom
            1762956000.100  4321  4321 E AndroidRuntime: 	at com.example.shop.Cart.checkout(Cart.kt:10)
            1762956001.000   777   777 F DEBUG   : *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
            1762956001.000   777   777 F DEBUG   : pid: 9876, tid: 9876, name: game  >>> com.example.game <<<
            1762956001.000   777   777 F DEBUG   : signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
            1762956001.000   777   777 F DEBUG   :       #00 pc 0000000000001234  /data/app/lib/arm64/libgame.so (Render::draw()+12)
            1762956002.000  1500  1600 I am_anr  : [0,2222,com.example.mail,952745540,Input dispatching timed out (Waited 5001ms)]
            1762956003.000   600   600 I lowmemorykiller: Kill 'com.example.news' (3333), uid 10140, oom_score_adj 905 to free 80520kB rss, 31188kB swap; reason: low watermark is breached
        """.trimIndent()
        val issues = LogcatParser.issues(LogcatParser.parseLines(log, nowMillis = 1762956100_000L))
        assertEquals(4, issues.size)

        val crash = issues.single { it.kind == IssueKind.CRASH }
        assertEquals("com.example.shop", crash.process)
        assertEquals("java.lang.IllegalStateException", crash.type)
        assertEquals(1762956000_100L, crash.timeMillis)

        val native = issues.single { it.kind == IssueKind.NATIVE_CRASH }
        assertEquals("com.example.game", native.process)
        assertEquals("SIGSEGV (SEGV_MAPERR)", native.type)
        assertEquals(listOf("libgame.so (Render::draw())"), native.frames)

        val anr = issues.single { it.kind == IssueKind.ANR }
        assertEquals("com.example.mail", anr.process)
        assertEquals("Input dispatching timed out", anr.type)

        val kill = issues.single { it.kind == IssueKind.LOW_MEMORY_KILL }
        assertEquals("com.example.news", kill.process)
        assertEquals(80520L, kill.freedKb)
        assertTrue(kill.message!!.contains("oom_score_adj 905"))
    }

    @Test fun parsesThreadtimeWithoutYear() {
        val now = java.time.LocalDateTime.of(2026, 1, 2, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val line = LogcatParser.parseLine("12-31 23:59:59.500  1  2 I tag: hi", now, ZoneOffset.UTC)!!
        val expected = java.time.LocalDateTime.of(2025, 12, 31, 23, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli() + 500
        assertEquals(expected, line.timeMillis)
        assertEquals("tag", line.tag)
        assertEquals("hi", line.message)
    }

    @Test fun groupsByRootCauseAndAppFrameIgnoringLineNumbers() {
        val a = CrashParser.parseJavaCrash(1_000L, javaCrash)
        val b = CrashParser.parseJavaCrash(5_000_000L, javaCrash.replace("CartActivity.kt:57", "CartActivity.kt:60"))
            .copy(pid = 999)
        val kill1 = Issue(IssueKind.LOW_MEMORY_KILL, 10L, "com.x", 1, "Killed", freedKb = 100)
        val kill2 = Issue(IssueKind.LOW_MEMORY_KILL, 20L, "com.x", 2, "Killed", freedKb = 50)

        val groups = IssueGrouper.group(listOf(kill1, a, kill2, b))
        assertEquals(2, groups.size)
        assertEquals(IssueKind.CRASH, groups[0].kind)        // crashes rank above kills
        assertEquals(2, groups[0].count)
        assertEquals(5_000_000L, groups[0].lastSeenMillis)
        assertEquals(150L, groups[1].totalFreedKb)
    }

    @Test fun dedupesSameNativeCrashFromTwoSources() {
        val full = CrashParser.parseNativeCrash(1_000L, nativeCrash)
        val bare = full.copy(timeMillis = 2_000L, frames = emptyList())
        val groups = IssueGrouper.group(listOf(bare, full))
        assertEquals(1, groups.single().count)
        assertEquals(3, groups.single().latest.frames.size)
    }

    @Test fun parsesMeminfo() {
        val m = MeminfoParser.parse("MemTotal:        7812345 kB\nSwapTotal:       4194300 kB\nSwapFree:        1000000 kB\nActive(anon):      12 kB\n")
        assertEquals(7812345L, m["MemTotal"])
        assertEquals(1000000L, m["SwapFree"])
        assertEquals(12L, m["Active(anon)"])
    }

    @Test fun digestFitsBudgetAndSummarizesKills() {
        val crashes = (1..50).map { n ->
            CrashParser.parseJavaCrash(n.toLong(), javaCrash.replace("CartActivity.onCreate", "Screen$n.onCreate"))
                .copy(pid = n)
        }
        val report = DeviceReport(
            collectedAtMillis = 3_600_000L,
            windowMillis = 72 * 3_600_000L,
            device = "Google Pixel 8, Android 15 (API 35)",
            access = AccessLevel.FULL,
            memory = MemorySnapshot(8L shl 30, 2L shl 30, 256L shl 20, false, 4L shl 30, 3L shl 30),
            groups = IssueGrouper.group(crashes + Issue(IssueKind.LOW_MEMORY_KILL, 1L, "com.news", 7, "Killed", freedKb = 2048)),
        )
        val digest = ReportDigest.build(report, maxChars = 3_000)
        assertTrue(digest.length <= 3_000)
        assertTrue(digest.contains("Totals: 50 app crashes"))
        assertTrue(digest.contains("com.news x1 (freed 2 MB)"))
        assertTrue(digest.contains("more issue groups omitted"))
        assertTrue(digest.contains("2.0 GB available of 8.0 GB (25%)"))
    }
}
