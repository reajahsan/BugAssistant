package com.reaj.bugassistant.storage

import java.time.Instant

/**
 * Writes saved issues as RFC 4180 CSV (UTF-8 with BOM so Excel detects the encoding).
 * Times are ISO-8601 UTC. Text cells that a spreadsheet would treat as a formula are prefixed with `'`,
 * since messages come from untrusted logs.
 */
object IssueCsv {
    val HEADER = listOf(
        "report_id", "source", "file", "device", "captured_at", "package", "process", "kind",
        "count", "first_seen", "last_seen", "type", "message", "cause", "frames",
    )

    fun write(rows: List<AppIssueView>, out: Appendable) {
        out.append('\uFEFF')
        out.appendRow(HEADER)
        rows.forEach { r ->
            out.appendRow(
                listOf(
                    r.reportId.toString(), r.access, text(r.sourceName), text(r.device), time(r.collectedAt),
                    text(r.packageName), text(r.process), r.kind, r.count.toString(),
                    time(r.firstSeen), time(r.lastSeen), text(r.type), text(r.message), text(r.cause), text(r.frames),
                ),
            )
        }
    }

    private fun Appendable.appendRow(cells: List<String>) {
        cells.forEachIndexed { i, cell ->
            if (i > 0) append(',')
            append(quote(cell))
        }
        append("\r\n")
    }

    private fun quote(cell: String): String =
        if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + cell.replace("\"", "\"\"") + "\""
        else cell

    private fun text(value: String?): String {
        val s = value.orEmpty()
        return if (s.isNotEmpty() && s[0] in FORMULA_START) "'$s" else s
    }

    private fun time(millis: Long): String = if (millis > 0) Instant.ofEpochMilli(millis).toString() else ""

    private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')
}
