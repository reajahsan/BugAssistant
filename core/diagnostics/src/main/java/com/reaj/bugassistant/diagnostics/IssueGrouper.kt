package com.reaj.bugassistant.diagnostics

/** Groups occurrences of the same problem and ranks the groups. */
object IssueGrouper {
    private val FRAMEWORK_PREFIXES = listOf(
        "java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.", "com.android.",
        "dalvik.", "libcore.", "sun.", "jdk.", "com.google.android.material.",
        "libc.so", "libart.so", "libbase.so",
    )

    /** DropBox can report the same native crash twice (app tag + SYSTEM_TOMBSTONE). */
    private const val DUPLICATE_WINDOW_MS = 60_000L

    fun group(issues: List<Issue>): List<IssueGroup> =
        dedupe(issues)
            .groupBy(::signature)
            .map { (sig, list) ->
                val sorted = list.sortedBy { it.timeMillis }
                IssueGroup(
                    signature = sig,
                    latest = sorted.last(),
                    count = sorted.size,
                    firstSeenMillis = sorted.first().timeMillis,
                    lastSeenMillis = sorted.last().timeMillis,
                    totalFreedKb = sorted.sumOf { it.freedKb ?: 0L },
                )
            }
            .sortedWith(
                compareBy<IssueGroup> { severity(it.kind) }
                    .thenByDescending { it.count }
                    .thenByDescending { it.lastSeenMillis },
            )

    fun signature(issue: Issue): String = when (issue.kind) {
        IssueKind.LOW_MEMORY_KILL -> "${issue.kind}|${issue.process}"
        IssueKind.LOW_MEMORY -> issue.kind.name
        else -> {
            val rootType = issue.cause?.substringBefore(':')?.trim()?.takeIf { issue.kind == IssueKind.CRASH }
            listOf(issue.kind.name, issue.process, rootType ?: issue.type, keyFrame(issue) ?: "").joinToString("|")
        }
    }

    /** First frame in app code, without line numbers, so different builds still group together. */
    fun keyFrame(issue: Issue): String? =
        (issue.frames.firstOrNull { f -> FRAMEWORK_PREFIXES.none { f.startsWith(it) } } ?: issue.frames.firstOrNull())
            ?.substringBefore("(")?.trim()

    private fun severity(kind: IssueKind) = when (kind) {
        IssueKind.CRASH, IssueKind.NATIVE_CRASH -> 0
        IssueKind.ANR -> 1
        IssueKind.MEMORY_LEAK -> 2
        IssueKind.LOW_MEMORY -> 3
        IssueKind.LOW_MEMORY_KILL -> 4
    }

    /**
     * Leak signals are repeated measurements, not duplicate reports of one event, so they are not de-duplicated.
     * Issues without a PID (e.g. native `am_crash` events) match the same process instead.
     */
    private fun dedupe(issues: List<Issue>): List<Issue> {
        val kept = mutableListOf<Issue>()
        for (issue in issues.sortedByDescending { it.frames.size }) {
            val dup = issue.kind != IssueKind.MEMORY_LEAK && kept.any {
                it.kind == issue.kind &&
                    (if (it.pid != null && issue.pid != null) it.pid == issue.pid else it.process == issue.process) &&
                    kotlin.math.abs(it.timeMillis - issue.timeMillis) < DUPLICATE_WINDOW_MS
            }
            if (!dup) kept += issue
        }
        return kept
    }
}
