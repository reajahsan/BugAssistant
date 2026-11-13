package com.reaj.bugassistant.diagnostics

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/**
 * Reads an Android bug report (the `.zip` from Developer options > Take bug report, or its main `.txt`)
 * into a [DeviceReport]: crashes, ANRs, native crashes and low-memory kills for every app, plus the
 * device signals behind "the phone is slow" (CPU load, thermal status, storage, memory hogs, UI jank).
 *
 * Bug reports are 10–200 MB of text, so the input is streamed line by line and only relevant lines are
 * kept. Pure Kotlin; unit tested on the JVM.
 */
object BugReportParser {

    /** Thrown when the input does not look like a bug report. */
    class NotABugReportException(message: String) : IllegalArgumentException(message)

    /**
     * @param fileName shown in the report and history.
     * @param defaultZone used when the report does not record the device time zone.
     */
    fun parse(input: InputStream, fileName: String, defaultZone: ZoneId = ZoneId.systemDefault()): DeviceReport {
        val acc = Accumulator()
        val stream = BufferedInputStream(input, 64 * 1024)
        if (isZip(stream)) {
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    when {
                        entry.isDirectory -> Unit
                        isMainEntry(name) -> acc.readMain(reader(zip))
                        TOMBSTONE_FILE.matches(name) && acc.tombstoneFiles < MAX_FILES ->
                            acc.addTombstone(readLimited(reader(zip), MAX_FILE_CHARS)).also { acc.tombstoneFiles++ }
                        ANR_FILE.matches(name) && acc.anrFiles < MAX_FILES ->
                            acc.addAnr(readLimited(reader(zip), MAX_FILE_CHARS)).also { acc.anrFiles++ }
                    }
                }
            }
        } else {
            stream.use { acc.readMain(reader(it)) }
        }
        // A picked .txt can be any text, e.g. the small dumpstate-*.txt status file next to the real zip.
        if (!acc.recognized && acc.rawIssues.isEmpty()) {
            throw NotABugReportException("No bug report found in $fileName")
        }
        return acc.build(fileName, defaultZone)
    }

    private fun isZip(stream: BufferedInputStream): Boolean {
        stream.mark(4)
        val magic = ByteArray(4)
        val n = stream.read(magic)
        stream.reset()
        return n == 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() &&
            magic[2] == 3.toByte() && magic[3] == 4.toByte()
    }

    /**
     * Main text at the root of the zip: `bugreport-<device>-<build>-<date>.txt`, or `dumpstate-<date>.txt`
     * on some devices (e.g. Samsung via `adb bugreport`). Not `dumpstate_board.txt` / `dumpstate_log.txt`.
     */
    private fun isMainEntry(name: String) =
        '/' !in name && name.endsWith(".txt") && (name.startsWith("bugreport") || name.startsWith("dumpstate-"))

    /** A reader that leaves the zip open when the entry is done. */
    private fun reader(input: InputStream) = BufferedReader(
        InputStreamReader(object : FilterInputStream(input) { override fun close() = Unit }, Charsets.UTF_8),
        64 * 1024,
    )

    private fun readLimited(reader: BufferedReader, max: Int): String {
        val buf = CharArray(max)
        var n = 0
        while (n < max) {
            val read = reader.read(buf, n, max - n)
            if (read < 0) break
            n += read
        }
        return String(buf, 0, n)
    }

    private class Accumulator {
        var sawMain = false
        var tombstoneFiles = 0
        var anrFiles = 0

        /** Times are device wall-clock time read as UTC; [build] converts them once the zone is known. */
        val rawIssues = mutableListOf<Issue>()
        private val logLines = mutableListOf<LogLine>()
        private var reportWallMillis: Long? = null
        private val props = HashMap<String, String>()
        private val meminfo = StringBuilder()

        private var uptime: String? = null
        private var cpuLoad: List<Double> = emptyList()
        private var cpuTotal: Double? = null
        private val topCpu = mutableListOf<ProcessStat>()
        private val topMemory = mutableListOf<ProcessStat>()
        private var memoryBlock = MemBlock.BEFORE
        private var storageFree: Long? = null
        private var storageTotal: Long? = null
        private var thermal: Int? = null

        private val processNames = HashMap<Int, String>()
        private val jankFrames = HashMap<Int, Long>()
        private val jankEvents = HashMap<Int, Int>()

        /** ART log lines that mean a Java heap hit its limit. */
        private val heapLines = mutableListOf<LogLine>()
        /** Per-app details from `dumpsys meminfo -a`, by pid. */
        private val memProcs = LinkedHashMap<Int, MemProc>()
        private var memProc: MemProc? = null

        private var section: String? = null
        /** True until the first timestamped line of the current log section is read. */
        private var logStartPending = false
        /** Device wall time (read as UTC) of the oldest log line; it bounds the window the report covers. */
        private var earliestLogMillis: Long? = null
        private var service: String? = null
        private val sectionText = StringBuilder()
        private var dropBoxTag: String? = null
        private var dropBoxTime = 0L
        private val dropBoxText = StringBuilder()

        fun readMain(reader: BufferedReader) {
            sawMain = true
            reader.lineSequence().forEach(::line)
            closeSection()
        }

        /** True once a dumpstate header or section is seen, i.e. the text really is a bug report. */
        var recognized = false
            private set

        private fun line(line: String) {
            if (line.startsWith("------ ")) {
                closeSection()
                if (!SECTION_END.matches(line)) SECTION_START.matchEntire(line)?.let { section = it.groupValues[1]; recognized = true }
                logStartPending = true
                return
            }
            if (line.startsWith("DUMP OF SERVICE ")) {
                service = SERVICE.find(line)?.groupValues?.get(1)
                memProc = null
                memoryBlock = if (service == "meminfo" && topMemory.isEmpty()) MemBlock.BEFORE else MemBlock.DONE
                return
            }
            if (line.startsWith("--------- ") && "was the duration of dumpsys" in line) {
                service = null
                return
            }
            val s = section
            when {
                service != null -> serviceLine(service!!, line)
                s == null -> headerLine(line)
                s.startsWith("SYSTEM LOG") || s.startsWith("EVENT LOG") || s.startsWith("LAST LOGCAT") -> logLine(line)
                s.startsWith("UPTIME") -> uptimeLine(line)
                s.startsWith("MEMORY INFO") -> if (meminfo.length < 16_384) meminfo.appendLine(line)
                s.startsWith("SYSTEM PROPERTIES") -> PROP.matchEntire(line)?.let { m ->
                    if (m.groupValues[1] in WANTED_PROPS) props[m.groupValues[1]] = m.groupValues[2]
                }
                s.startsWith("DROPBOX") -> dropBoxLine(line)
                // Not "VM TRACES JUST NOW": that is a routine stack dump of every app, not an ANR.
                s.startsWith("VM TRACES AT LAST ANR") || s.startsWith("TOMBSTONE") ->
                    if (sectionText.length < MAX_FILE_CHARS) sectionText.appendLine(line)
            }
        }

        private fun closeSection() {
            val s = section
            if (s != null && sectionText.isNotEmpty()) {
                val text = sectionText.toString()
                if (s.startsWith("TOMBSTONE")) addTombstone(text) else addAnr(text)
            }
            flushDropBox()
            sectionText.setLength(0)
            section = null
            service = null
        }

        private fun headerLine(line: String) {
            DUMPSTATE_TIME.find(line)?.let { reportWallMillis = wallMillis(it.groupValues[1]) }
            uptimeLine(line)
        }

        private fun uptimeLine(line: String) {
            val m = UPTIME.find(line) ?: return
            if (uptime == null) uptime = "up " + m.groupValues[1].replace(Regex("""\s+"""), " ").trim()
            if (cpuLoad.isEmpty()) cpuLoad = listOf(m.groupValues[2], m.groupValues[3], m.groupValues[4]).map { it.toDouble() }
        }

        private fun logLine(line: String) {
            if (logStartPending && line.firstOrNull()?.isDigit() == true) {
                LogcatParser.parseLine(line, reportWallMillis ?: System.currentTimeMillis(), ZoneOffset.UTC)?.let { l ->
                    logStartPending = false
                    earliestLogMillis = minOf(earliestLogMillis ?: l.timeMillis, l.timeMillis)
                }
            }
            if (HEAP_MARKERS.any { it in line }) {
                if (heapLines.size < MAX_HEAP_LINES) {
                    LogcatParser.parseLine(line, reportWallMillis ?: System.currentTimeMillis(), ZoneOffset.UTC)
                        ?.let { heapLines += it }
                }
                return
            }
            if (logLines.size >= MAX_LOG_LINES || RELEVANT_TAGS.none { it in line }) return
            val l = LogcatParser.parseLine(line, reportWallMillis ?: System.currentTimeMillis(), ZoneOffset.UTC) ?: return
            when (l.tag) {
                "Choreographer" -> SKIPPED_FRAMES.find(l.message)?.let {
                    jankFrames.merge(l.pid, it.groupValues[1].toLong(), Long::plus)
                    jankEvents.merge(l.pid, 1, Int::plus)
                }
                "OpenGLRenderer" -> if (l.message.startsWith("Davey!")) jankEvents.merge(l.pid, 1, Int::plus)
                "ActivityManager" -> START_PROC.find(l.message)?.let {
                    processNames[it.groupValues[1].toInt()] = it.groupValues[2]
                }
                "am_proc_start" -> l.message.trim('[', ']', ' ').split(",").let { p ->
                    if (p.size >= 4) p[1].trim().toIntOrNull()?.let { pid -> processNames[pid] = p[3].trim() }
                }
                in ISSUE_TAGS -> logLines += l
            }
        }

        private fun dropBoxLine(line: String) {
            if (line.startsWith("========")) {
                flushDropBox()
                return
            }
            val header = DROPBOX_HEADER.matchEntire(line)
            if (header != null && dropBoxText.isEmpty()) {
                flushDropBox()
                dropBoxTag = header.groupValues[2]
                dropBoxTime = wallMillis(header.groupValues[1])
                return
            }
            if (dropBoxTag != null && dropBoxText.length < MAX_FILE_CHARS) dropBoxText.appendLine(line)
        }

        private fun flushDropBox() {
            val tag = dropBoxTag
            if (tag != null && dropBoxText.isNotBlank()) {
                CrashParser.kindForDropBoxTag(tag)?.let { kind ->
                    addIssue(CrashParser.parse(kind, dropBoxTime, dropBoxText.toString()))
                }
            }
            dropBoxTag = null
            dropBoxText.setLength(0)
        }

        private fun serviceLine(service: String, line: String) {
            when (service) {
                "meminfo" -> memoryLine(line)
                "cpuinfo" -> {
                    if (cpuLoad.isEmpty()) CPU_LOAD.find(line)?.let { m ->
                        cpuLoad = (1..3).map { m.groupValues[it].toDouble() }
                    }
                    CPU_TOTAL.find(line)?.let { if (cpuTotal == null) cpuTotal = it.groupValues[1].toDouble() }
                    CPU_PROCESS.find(line)?.let { m ->
                        if (topCpu.size < TOP_N && cpuTotal == null) {
                            topCpu += ProcessStat(m.groupValues[3].trim(), m.groupValues[1].toDouble())
                            processNames.putIfAbsent(m.groupValues[2].toInt(), m.groupValues[3].trim())
                        }
                    }
                }
                "diskstats" -> DATA_FREE.find(line)?.let {
                    storageFree = it.groupValues[1].toLong() * 1024
                    storageTotal = it.groupValues[2].toLong() * 1024
                }
                "thermalservice" -> if (thermal == null) THERMAL.find(line)?.let { thermal = it.groupValues[1].toInt() }
            }
        }

        /**
         * The first "Total PSS/RSS by process" list in `dumpsys meminfo`, already sorted largest first.
         * Before it, `meminfo -a` prints a detailed block per app, read by [memProcLine].
         */
        private fun memoryLine(line: String) {
            if (memProcLine(line)) return
            when (memoryBlock) {
                MemBlock.BEFORE -> if (MEM_BLOCK_START.containsMatchIn(line)) {
                    memoryBlock = MemBlock.IN
                    memProc = null
                }
                MemBlock.IN -> {
                    val m = MEM_PROCESS.find(line)
                    when {
                        m != null -> {
                            val pid = m.groupValues[3].toInt()
                            processNames.putIfAbsent(pid, m.groupValues[2])
                            if (topMemory.size < TOP_N) {
                                topMemory += ProcessStat(m.groupValues[2], m.groupValues[1].replace(",", "").toDouble())
                            }
                        }
                        line.isBlank() && topMemory.isNotEmpty() -> memoryBlock = MemBlock.DONE
                    }
                }
                MemBlock.DONE -> Unit
            }
        }

        /** `** MEMINFO in pid 4321 [com.acme] **` blocks: Java heap use and live object counts. */
        private fun memProcLine(line: String): Boolean {
            MEM_PROC_HEADER.find(line)?.let { m ->
                val pid = m.groupValues[1].toInt()
                val name = m.groupValues[2]
                processNames.putIfAbsent(pid, name)
                // A second meminfo dump repeats the blocks; keep the first.
                memProc = if (pid in memProcs) null else MemProc(name).also { memProcs[pid] = it }
                return true
            }
            val p = memProc ?: return false
            DALVIK_HEAP.find(line)?.let { m ->
                val n = m.groupValues[1].trim().split(Regex("""\s+""")).map { it.toLong() }
                if (n.size >= 3) {
                    p.heapSizeKb = n[n.size - 3]
                    p.heapAllocKb = n[n.size - 2]
                }
            }
            ACTIVITIES.find(line)?.let { p.activities = it.groupValues[1].toInt() }
            APP_CONTEXTS.find(line)?.let { p.appContexts = it.groupValues[1].toInt() }
            VIEWS.find(line)?.let { p.views = it.groupValues[1].toInt() }
            return false
        }

        /** Leak suspects from ART heap logs and `meminfo -a`. Log times are device wall-clock like [rawIssues]. */
        private fun leakIssues(): List<Issue> {
            // Newer ART logs use the process name as the tag; older ones use "art".
            fun name(l: LogLine) = processNames[l.pid] ?: l.tag.takeIf { PACKAGE_TAG.matches(it) } ?: "pid ${l.pid}"
            val fromLogs = heapLines.map { l ->
                val oom = "Throwing OutOfMemoryError" in l.message
                Issue(
                    kind = IssueKind.MEMORY_LEAK,
                    timeMillis = l.timeMillis,
                    process = name(l),
                    pid = l.pid,
                    type = if (oom) "OutOfMemoryError thrown" else "Java heap at its limit",
                    message = l.message.trim().take(300),
                )
            }
            val growth = props["dalvik.vm.heapgrowthlimit"]?.let(::sizeKb)
            val max = props["dalvik.vm.heapsize"]?.let(::sizeKb)
            val fromMeminfo = memProcs.flatMap { (pid, p) ->
                buildList {
                    val alloc = p.heapAllocKb
                    // A heap already larger than the growth limit means the app uses android:largeHeap.
                    val limit = if (growth != null && (p.heapSizeKb ?: 0) <= growth) growth else max ?: growth
                    if (alloc != null && limit != null && limit > 0 && alloc * 100 >= limit * HEAP_NEAR_LIMIT_PERCENT) {
                        add(
                            Issue(
                                kind = IssueKind.MEMORY_LEAK, timeMillis = 0, process = p.name, pid = pid,
                                type = "Java heap near its limit",
                                message = "Java heap ${alloc / 1024} MB used of ${limit / 1024} MB limit (${alloc * 100 / limit}%)",
                            ),
                        )
                    }
                    val activities = p.activities
                    if (activities != null && activities >= LEAK_ACTIVITY_THRESHOLD) {
                        add(
                            Issue(
                                kind = IssueKind.MEMORY_LEAK, timeMillis = 0, process = p.name, pid = pid,
                                type = "Many Activity objects in memory",
                                message = "$activities Activities in memory" +
                                    (p.appContexts?.let { ", $it AppContexts" } ?: "") +
                                    (p.views?.let { ", $it Views" } ?: ""),
                            ),
                        )
                    }
                }
            }
            return fromLogs + fromMeminfo
        }

        fun addTombstone(text: String) {
            if ("*** *** ***" !in text && ">>> " !in text) return
            val time = TOMBSTONE_TIME.find(text)?.let { wallMillis(it.groupValues[1]) } ?: 0L
            addIssue(CrashParser.parseNativeCrash(time, text))
        }

        fun addAnr(text: String) {
            if ("\"main\"" !in text && "Subject:" !in text) return
            val header = ANR_HEADER.find(text)
            val time = header?.let { wallMillis(it.groupValues[2]) } ?: 0L
            val issue = CrashParser.parseAnr(time, text)
            addIssue(if (issue.pid == null && header != null) issue.copy(pid = header.groupValues[1].toInt()) else issue)
        }

        private fun addIssue(issue: Issue) {
            if (rawIssues.size < MAX_ISSUES) rawIssues += issue
        }

        fun build(fileName: String, defaultZone: ZoneId): DeviceReport {
            val zone = props["persist.sys.timezone"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: defaultZone
            fun real(wall: Long) = LocalDateTime.ofEpochSecond(Math.floorDiv(wall, 1000L), 0, ZoneOffset.UTC)
                .atZone(zone).toInstant().toEpochMilli() + Math.floorMod(wall, 1000L)

            // Crashes name their PID ("Process: x, PID: n"), so register them before naming the heap log lines.
            val crashes = LogcatParser.issues(logLines) + rawIssues
            crashes.forEach { i -> i.pid?.let { pid -> if (i.process != "unknown") processNames.putIfAbsent(pid, i.process) } }
            // Undated tombstones/ANR files (time 0) are stamped with the report time below.
            val issues = (crashes + leakIssues())
                .map { it.copy(timeMillis = if (it.timeMillis > 0) real(it.timeMillis) else 0) }
            issues.forEach { i -> i.pid?.let { pid -> if (!i.process.startsWith("pid ")) processNames.putIfAbsent(pid, i.process) } }

            val reportTime = reportWallMillis?.let(::real)
                ?: issues.maxOfOrNull { it.timeMillis }?.takeIf { it > 0 }
                ?: System.currentTimeMillis()
            val dated = issues.map { if (it.timeMillis == 0L) it.copy(timeMillis = reportTime) else it }
            // Tombstones and dropbox files survive reboots and can be years old, so the logs set the window.
            val logStart = earliestLogMillis?.let(::real)?.takeIf { it <= reportTime }
            val earliest = logStart ?: dated.minOfOrNull { it.timeMillis } ?: reportTime
            val older = dated.filter { logStart != null && it.timeMillis < logStart }

            val jank = jankEvents.keys.map { pid ->
                ProcessStat(processNames[pid] ?: "pid $pid", (jankFrames[pid] ?: 0L).toDouble(), jankEvents[pid] ?: 0)
            }.groupBy { it.process }
                .map { (name, list) -> ProcessStat(name, list.sumOf { it.value }, list.sumOf { it.events }) }
                .sortedWith(compareByDescending<ProcessStat> { it.value }.thenByDescending { it.events })
                .take(TOP_N)

            val health = DeviceHealth(
                uptime = uptime,
                cpuLoad = cpuLoad,
                cpuTotalPercent = cpuTotal,
                topCpu = topCpu.sortedByDescending { it.value },
                topMemory = topMemory,
                storageFreeBytes = storageFree,
                storageTotalBytes = storageTotal,
                thermalStatus = thermal,
                jank = jank,
            )
            val notes = buildList {
                if (!sawMain) add("Bug report text not found; only tombstone and ANR files were read.")
                if (logLines.size >= MAX_LOG_LINES) add("Log too large; only the first $MAX_LOG_LINES relevant lines were read.")
                if (heapLines.size >= MAX_HEAP_LINES) add("More than $MAX_HEAP_LINES Java heap log lines; the rest were skipped.")
                if (rawIssues.size >= MAX_ISSUES) add("More than $MAX_ISSUES crash records; the rest were skipped.")
                if (older.isNotEmpty()) {
                    val oldest = java.time.Instant.ofEpochMilli(older.minOf { it.timeMillis }).toString().take(10)
                    add("${older.size} crash records predate the logs (oldest $oldest); they come from crash files kept across reboots.")
                }
            }
            return DeviceReport(
                collectedAtMillis = reportTime,
                windowMillis = (reportTime - earliest).coerceAtLeast(0),
                device = deviceName(),
                access = AccessLevel.BUG_REPORT,
                memory = memory(),
                groups = IssueGrouper.group(dated),
                notes = notes,
                sourceName = fileName,
                health = health,
            )
        }

        private fun deviceName(): String {
            val model = listOfNotNull(props["ro.product.manufacturer"], props["ro.product.model"])
                .joinToString(" ").ifBlank { "Unknown device" }
            val release = props["ro.build.version.release"]
            val sdk = props["ro.build.version.sdk"]
            return when {
                release != null && sdk != null -> "$model, Android $release (API $sdk)"
                release != null -> "$model, Android $release"
                else -> model
            }
        }

        private fun memory(): MemorySnapshot? {
            val m = MeminfoParser.parse(meminfo.toString())
            val total = m["MemTotal"] ?: return null
            val available = m["MemAvailable"] ?: ((m["MemFree"] ?: 0) + (m["Cached"] ?: 0))
            return MemorySnapshot(
                totalBytes = total * 1024,
                availableBytes = available * 1024,
                swapTotalBytes = m["SwapTotal"]?.times(1024),
                swapFreeBytes = m["SwapFree"]?.times(1024),
            )
        }
    }

    private enum class MemBlock { BEFORE, IN, DONE }

    private class MemProc(val name: String) {
        var heapSizeKb: Long? = null
        var heapAllocKb: Long? = null
        var activities: Int? = null
        var appContexts: Int? = null
        var views: Int? = null
    }

    /** `256m` / `512M` / `1g` / `262144k` (system property format) to kB. */
    private fun sizeKb(text: String): Long? {
        val m = Regex("""^\s*(\d+)\s*([kKmMgG]?)\s*$""").matchEntire(text) ?: return null
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2].lowercase()) {
            "k" -> n
            "m" -> n * 1024
            "g" -> n * 1024 * 1024
            else -> n / 1024
        }
    }

    /** Device wall-clock `yyyy-MM-dd HH:mm:ss` read as if it were UTC. */
    private fun wallMillis(text: String): Long =
        LocalDateTime.parse(text, WALL_TIME).toEpochSecond(ZoneOffset.UTC) * 1000

    private val WALL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private const val MAX_FILES = 64
    private const val MAX_FILE_CHARS = 128 * 1024
    private const val MAX_LOG_LINES = 100_000
    private const val MAX_ISSUES = 5_000
    private const val TOP_N = 8
    private const val MAX_HEAP_LINES = 5_000
    /** Java heap use at or above this share of the app's limit is reported. */
    private const val HEAP_NEAR_LIMIT_PERCENT = 85
    /** An app rarely shows this many screens at once; more usually means destroyed Activities are still referenced. */
    private const val LEAK_ACTIVITY_THRESHOLD = 5

    private val TOMBSTONE_FILE = Regex("""^FS/data/tombstones/tombstone_\d+$""")
    private val ANR_FILE = Regex("""^FS/data/anr/(?:anr_|traces).*""")

    private val SECTION_START = Regex("""^------ (.+?)(?: \(.*\))? ------$""")
    private val SECTION_END = Regex("""^------ [\d.]+s was the duration of .*""")
    private val SERVICE = Regex("""^DUMP OF SERVICE (?:(?:CRITICAL|HIGH|NORMAL) )?(\S+?):""")
    private val PROP = Regex("""^\[(.+?)\]: \[(.*)\]$""")
    private val WANTED_PROPS = setOf(
        "ro.product.manufacturer", "ro.product.model", "ro.build.version.release",
        "ro.build.version.sdk", "persist.sys.timezone", "dalvik.vm.heapgrowthlimit", "dalvik.vm.heapsize",
    )

    private val DUMPSTATE_TIME = Regex("""^== dumpstate: (\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)""")
    private val UPTIME = Regex("""\bup (.+?),\s+(?:\d+ users?,\s+)?load average: ([\d.]+), ([\d.]+), ([\d.]+)""")
    private val DROPBOX_HEADER = Regex("""^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) (\S+) \(.*""")
    private val TOMBSTONE_TIME = Regex("""^Timestamp: (\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)""", RegexOption.MULTILINE)
    private val ANR_HEADER = Regex("""^----- pid (\d+) at (\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)""", RegexOption.MULTILINE)

    private val ISSUE_TAGS = setOf("AndroidRuntime", "DEBUG", "am_anr", "am_crash", "am_low_memory", "lowmemorykiller")
    private val RELEVANT_TAGS = ISSUE_TAGS + listOf("Choreographer", "OpenGLRenderer", "ActivityManager", "am_proc_start")
    private val SKIPPED_FRAMES = Regex("""Skipped (\d+) frames""")
    private val START_PROC = Regex("""Start proc (\d+):([^/\s]+)/""")

    private val CPU_LOAD = Regex("""^Load: ([\d.]+) / ([\d.]+) / ([\d.]+)""")
    private val CPU_PROCESS = Regex("""^\s*([\d.]+)% (\d+)/([^:]+): """)
    private val CPU_TOTAL = Regex("""^\s*([\d.]+)% TOTAL:""")
    private val MEM_BLOCK_START = Regex("""^Total (?:PSS|RSS) by process:""")
    private val MEM_PROCESS = Regex("""^\s*([\d,]+)K: (\S+) \(pid (\d+)""")
    private val DATA_FREE = Regex("""^Data-Free: (\d+)K / (\d+)K total""")
    private val THERMAL = Regex("""^\s*Thermal Status: (\d+)""")

    /** ART messages logged when a Java heap reaches its limit. The tag is "art" or the process name. */
    private val PACKAGE_TAG = Regex("""[a-zA-Z]\w*(\.[a-zA-Z]\w*)+(:\w+)?""")
    private val HEAP_MARKERS = listOf(
        "Throwing OutOfMemoryError",
        "Clamp target GC heap from",
        "Forcing collection of SoftReferences",
    )
    private val MEM_PROC_HEADER = Regex("""^\s*\*\* MEMINFO in pid (\d+) \[([^\]]+)\] \*\*""")
    /** Last three columns are Heap Size, Heap Alloc and Heap Free (kB) on every Android version. */
    private val DALVIK_HEAP = Regex("""^\s*Dalvik Heap((?:\s+\d+)+)\s*$""")
    private val ACTIVITIES = Regex("""\bActivities:\s+(\d+)""")
    private val APP_CONTEXTS = Regex("""\bAppContexts:\s+(\d+)""")
    private val VIEWS = Regex("""(?:^|\s)Views:\s+(\d+)""")
}
