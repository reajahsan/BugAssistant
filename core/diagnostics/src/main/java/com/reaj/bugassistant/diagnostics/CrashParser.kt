package com.reaj.bugassistant.diagnostics

/**
 * Parses crash, ANR, tombstone and low-memory text in the formats Android writes to DropBox,
 * the logcat crash buffer and ApplicationExitInfo traces. Pure Kotlin, so it is unit tested on the JVM.
 */
object CrashParser {
    const val MAX_FRAMES = 12

    private val PROCESS = Regex("""^Process: ([^,\s]+)""", RegexOption.MULTILINE)
    private val PID = Regex("""\bPID: (\d+)""")
    private val JAVA_FRAME = Regex("""^\s+at (.+)$""")

    private val SIGNAL = Regex("""signal \d+ \((\w+)\)(?:, code -?\d+ \((\w+)\))?(?:, fault addr (\S+))?""")
    private val ABORT = Regex("""Abort message: '(.*)'""")
    private val TOMBSTONE_PROCESS = Regex(""">>> (.+?) <<<""")
    private val TOMBSTONE_PID = Regex("""\bpid: (\d+), tid:""")
    private val NATIVE_FRAME = Regex("""^\s*(?:native: )?#(\d+) pc [0-9a-fA-F]+\s+(\S+)(?:\s+\((.+?)\)(?=\s+\(BuildId|\s*$))?""")

    private val SUBJECT = Regex("""^Subject: (.*)$""", RegexOption.MULTILINE)
    private val MAIN_THREAD = Regex("""^"main" .*?(\w+)\s*$""")
    private val LOCK_WAIT = Regex("""^\s+- (waiting (?:to lock|on) .*)$""")

    /** Maps a DropBox tag (e.g. `data_app_crash`, `SYSTEM_TOMBSTONE`) to a kind, or null if it is not tracked. */
    fun kindForDropBoxTag(tag: String): IssueKind? = when {
        tag.endsWith("_native_crash") || tag == "SYSTEM_TOMBSTONE" -> IssueKind.NATIVE_CRASH
        tag.endsWith("_crash") -> IssueKind.CRASH
        tag.endsWith("_anr") || tag == "system_server_watchdog" -> IssueKind.ANR
        tag.endsWith("_lowmem") -> IssueKind.LOW_MEMORY
        else -> null
    }

    fun parse(kind: IssueKind, timeMillis: Long, text: String): Issue = when (kind) {
        IssueKind.CRASH -> parseJavaCrash(timeMillis, text)
        IssueKind.NATIVE_CRASH -> parseNativeCrash(timeMillis, text)
        IssueKind.ANR -> parseAnr(timeMillis, text)
        IssueKind.LOW_MEMORY -> Issue(
            kind = IssueKind.LOW_MEMORY,
            timeMillis = timeMillis,
            process = "system",
            type = "System low on memory",
        )
        IssueKind.LOW_MEMORY_KILL, IssueKind.MEMORY_LEAK ->
            throw IllegalArgumentException("$kind is not a DropBox entry")
    }

    fun parseJavaCrash(timeMillis: Long, text: String): Issue {
        val lines = text.lines()
        val firstFrame = lines.indexOfFirst { JAVA_FRAME.matches(it) }
        val header = if (firstFrame > 0) {
            lines.subList(0, firstFrame).lastOrNull { it.isNotBlank() }?.trim()
        } else {
            null
        }
        val causeIdx = lines.indexOfLast { it.trimStart().startsWith("Caused by: ") }
        val cause = if (causeIdx >= 0) lines[causeIdx].trim().removePrefix("Caused by: ") else null
        // The root cause's frames point at the failing app code; wrappers usually show only framework frames.
        val framesFrom = if (causeIdx >= 0) causeIdx + 1 else firstFrame
        val frames = if (framesFrom >= 0) {
            lines.drop(framesFrom).takeWhile { JAVA_FRAME.matches(it) }.map { it.trim().removePrefix("at ") }
        } else {
            emptyList()
        }
        return Issue(
            kind = IssueKind.CRASH,
            timeMillis = timeMillis,
            process = processOf(text) ?: "unknown",
            pid = pidOf(text),
            type = header?.substringBefore(':')?.trim()?.ifEmpty { null } ?: "Unknown exception",
            message = header?.take(300),
            cause = cause?.take(300),
            frames = frames.take(MAX_FRAMES),
        )
    }

    fun parseNativeCrash(timeMillis: Long, text: String): Issue {
        val signal = SIGNAL.find(text)
        val abort = ABORT.find(text)?.groupValues?.get(1)
        val type = signal?.let { m ->
            val code = m.groupValues[2]
            if (code.isNotEmpty()) "${m.groupValues[1]} ($code)" else m.groupValues[1]
        } ?: "Native crash"
        val message = abort ?: signal?.groupValues?.get(3)?.takeIf { it.isNotEmpty() }?.let { "fault addr $it" }

        val frames = mutableListOf<String>()
        for (line in text.lineSequence()) {
            val m = NATIVE_FRAME.find(line) ?: continue
            if (m.groupValues[1].toInt() == 0 && frames.isNotEmpty()) break   // only the crashing thread
            frames += nativeFrame(m)
            if (frames.size == MAX_FRAMES) break
        }
        return Issue(
            kind = IssueKind.NATIVE_CRASH,
            timeMillis = timeMillis,
            process = TOMBSTONE_PROCESS.find(text)?.groupValues?.get(1) ?: processOf(text) ?: "unknown",
            pid = TOMBSTONE_PID.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: pidOf(text),
            type = type,
            message = message?.take(300),
            frames = frames,
        )
    }

    /** Parses a DropBox ANR entry, or a bare `traces.txt` dump (then [reason] supplies the subject). */
    fun parseAnr(timeMillis: Long, text: String, reason: String? = null): Issue {
        val subject = reason ?: SUBJECT.find(text)?.groupValues?.get(1)?.trim()
        val lines = text.lines()
        val mainIdx = lines.indexOfFirst { it.startsWith("\"main\" ") }

        var state: String? = null
        var lockWait: String? = null
        val javaFrames = mutableListOf<String>()
        val nativeFrames = mutableListOf<String>()
        if (mainIdx >= 0) {
            state = MAIN_THREAD.find(lines[mainIdx])?.groupValues?.get(1)
            for (line in lines.drop(mainIdx + 1)) {
                if (line.isBlank()) break
                JAVA_FRAME.matchEntire(line)?.let { javaFrames += it.groupValues[1].trim() }
                if (lockWait == null) LOCK_WAIT.matchEntire(line)?.let { lockWait = it.groupValues[1] }
                if (line.trimStart().startsWith("native: ")) NATIVE_FRAME.find(line)?.let { nativeFrames += nativeFrame(it) }
            }
        }
        val message = listOfNotNull(subject, state?.let { "main thread: $it" }).joinToString(" · ")
        return Issue(
            kind = IssueKind.ANR,
            timeMillis = timeMillis,
            process = processOf(text) ?: CMD_LINE.find(text)?.groupValues?.get(1)?.trim() ?: "unknown",
            pid = pidOf(text),
            type = subject?.let(::normalizeAnrReason) ?: "ANR",
            message = message.ifEmpty { null }?.take(300),
            cause = lockWait,
            frames = javaFrames.ifEmpty { nativeFrames }.take(MAX_FRAMES),
        )
    }

    private val CMD_LINE = Regex("""^Cmd line: (.+)$""", RegexOption.MULTILINE)

    /** "Input dispatching timed out (Waiting to send ... )" -> "Input dispatching timed out". */
    fun normalizeAnrReason(reason: String): String =
        reason.substringBefore(" (").substringBefore(" {").substringBefore(". ").trim().take(120)

    private fun processOf(text: String) = PROCESS.find(text)?.groupValues?.get(1)
    private fun pidOf(text: String) = PID.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /** `/apex/.../libc.so (abort+164) (BuildId: ...)` -> `libc.so (abort)`. */
    private fun nativeFrame(m: MatchResult): String {
        val lib = m.groupValues[2].substringAfterLast('/')
        val fn = m.groupValues[3].takeUnless { it.isEmpty() || it.startsWith("BuildId") }
            ?.replace(Regex("""\+\d+$"""), "")
        return if (fn != null) "$lib ($fn)" else lib
    }
}
