package com.reaj.bugassistant.diagnostics

import java.time.LocalDateTime
import java.time.ZoneId

data class LogLine(
    val timeMillis: Long,
    val pid: Int,
    val priority: Char,
    val tag: String,
    val message: String,
)

/** Turns `logcat -v epoch` or `-v threadtime` (optionally `-v uid`, as in bug reports) output into [Issue]s. */
object LogcatParser {
    private val LINE = Regex(
        """^\s*(?:(\d+)\.(\d{3})|(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3}))\s+(?:[\w.]+\s+)?(\d+)\s+\d+\s+([VDIWEFA])\s+(.*?)\s*:(?: (.*))?$""",
    )
    private val AM_CRASH_TAIL = Regex("""^(.*?),([^,]*),(-?\d+)(?:,(\d+))?$""")
    private val LMK_KILL = Regex("""Kill(?:ing)? '([^']+)' \((\d+)\)""")
    private val LMK_ADJ = Regex("""(?:oom_score_adj|adj) (-?\d+)""")
    private val LMK_FREED = Regex("""to free (\d+)kB""")
    private val LMK_REASON = Regex("""reason: (.+)$""")

    fun parseLines(
        text: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<LogLine> = text.lineSequence().mapNotNull { parseLine(it, nowMillis, zone) }.toList()

    fun parseLine(line: String, nowMillis: Long, zone: ZoneId): LogLine? {
        val m = LINE.matchEntire(line) ?: return null
        val g = m.groupValues
        val time = if (g[1].isNotEmpty()) {
            g[1].toLong() * 1000 + g[2].toLong()
        } else {
            // threadtime has no year: assume the current one, unless that lands in the future.
            val year = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).year
            fun at(y: Int) = LocalDateTime.of(y, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt(), g[7].toInt())
                .atZone(zone).toInstant().toEpochMilli() + g[8].toLong()
            at(year).let { if (it > nowMillis + 86_400_000L) at(year - 1) else it }
        }
        return LogLine(time, g[9].toInt(), g[10][0], g[11], g[12])
    }

    /** Extracts Java crashes, native crashes, ANRs, `am_crash`/`am_low_memory` events and lmkd kills. */
    fun issues(lines: List<LogLine>): List<Issue> {
        val out = mutableListOf<Issue>()
        var block: MutableList<LogLine>? = null
        var blockTag = ""

        fun flush() {
            val b = block ?: return
            val text = b.joinToString("\n") { it.message }
            out += if (blockTag == "AndroidRuntime") CrashParser.parseJavaCrash(b.first().timeMillis, text)
            else CrashParser.parseNativeCrash(b.first().timeMillis, text)
            block = null
        }

        for (l in lines) {
            when (l.tag) {
                "AndroidRuntime" -> when {
                    l.message.startsWith("FATAL EXCEPTION") -> { flush(); block = mutableListOf(l); blockTag = l.tag }
                    blockTag == l.tag && block?.first()?.pid == l.pid -> block?.add(l)
                }
                "DEBUG" -> when {
                    l.message.startsWith("*** *** ***") -> { flush(); block = mutableListOf(l); blockTag = l.tag }
                    blockTag == l.tag && block?.first()?.pid == l.pid -> block?.add(l)
                }
                "am_anr" -> anr(l)?.let { out += it }
                "am_crash" -> amCrash(l)?.let { out += it }
                "am_low_memory" -> out += Issue(
                    kind = IssueKind.LOW_MEMORY,
                    timeMillis = l.timeMillis,
                    process = "system",
                    type = "System low on memory",
                )
                "lowmemorykiller" -> lmkKill(l)?.let { out += it }
            }
        }
        flush()
        return out
    }

    /**
     * `[user,pid,process,flags,exception,message,file,line(,recoverable)]`. Only a summary line, but it
     * survives after the crash buffer has rotated. The full stack, when present, wins in [IssueGrouper].
     */
    private fun amCrash(l: LogLine): Issue? {
        val parts = l.message.trim().removePrefix("[").removeSuffix("]").split(",", limit = 6)
        if (parts.size < 6) return null
        val exception = parts[4].trim()
        val message = AM_CRASH_TAIL.matchEntire(parts[5])?.groupValues?.get(1) ?: parts[5]
        val native = exception.equals("Native crash", ignoreCase = true)
        // Android ≤ 15 logs [user,pid,...]; Android 16 logs [pid,user,...]. User IDs are small, so take the larger.
        val pid = listOfNotNull(parts[0].trim().toIntOrNull(), parts[1].trim().toIntOrNull()).maxOrNull()
        return Issue(
            kind = if (native) IssueKind.NATIVE_CRASH else IssueKind.CRASH,
            timeMillis = l.timeMillis,
            process = parts[2].trim(),
            // For native crashes the event is written by system_server's crash listener, so its PID is not the app's.
            pid = if (native) null else pid,
            type = exception.ifEmpty { "Unknown exception" },
            message = message.trim().ifEmpty { null }?.let { if (native) it else "$exception: $it" }?.take(300),
        )
    }

    /** `[0,1234,com.example,952745540,Input dispatching timed out (...)]` (user, pid, process, flags, reason). */
    private fun anr(l: LogLine): Issue? {
        val parts = l.message.trim().removePrefix("[").removeSuffix("]").split(",", limit = 5)
        if (parts.size < 5) return null
        val reason = parts[4].trim()
        return Issue(
            kind = IssueKind.ANR,
            timeMillis = l.timeMillis,
            process = parts[2].trim(),
            pid = parts[1].trim().toIntOrNull(),
            type = CrashParser.normalizeAnrReason(reason),
            message = reason.take(300),
        )
    }

    private fun lmkKill(l: LogLine): Issue? {
        val m = LMK_KILL.find(l.message) ?: return null
        val adj = LMK_ADJ.find(l.message)?.groupValues?.get(1)
        val reason = LMK_REASON.find(l.message)?.groupValues?.get(1)
        return Issue(
            kind = IssueKind.LOW_MEMORY_KILL,
            timeMillis = l.timeMillis,
            process = m.groupValues[1],
            pid = m.groupValues[2].toIntOrNull(),
            type = "Killed by low-memory killer",
            message = listOfNotNull(adj?.let { "oom_score_adj $it" }, reason).joinToString(" · ").ifEmpty { null },
            freedKb = LMK_FREED.find(l.message)?.groupValues?.get(1)?.toLongOrNull(),
        )
    }
}
