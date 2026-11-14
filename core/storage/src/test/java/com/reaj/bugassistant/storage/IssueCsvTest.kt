package com.reaj.bugassistant.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueCsvTest {

    private fun row(message: String?, frames: String = "at a.B.c(B.kt:1)") = AppIssueView(
        packageName = "com.acme.field",
        process = "com.acme.field:sync",
        kind = "CRASH",
        count = 3,
        type = "java.lang.IllegalStateException",
        message = message,
        cause = null,
        frames = frames,
        firstSeen = 0,
        lastSeen = 1_700_000_000_000,
        reportId = 7,
        access = "BUG_REPORT",
        sourceName = "bugreport.zip",
        device = "Pixel 8",
        collectedAt = 1_700_000_000_000,
    )

    private fun csv(vararg rows: AppIssueView) = StringBuilder().also { IssueCsv.write(rows.toList(), it) }.toString()

    @Test fun headerAndRow() {
        val lines = csv(row("boom")).removePrefix("\uFEFF").split("\r\n")
        assertEquals(IssueCsv.HEADER.joinToString(","), lines[0])
        assertEquals(
            "7,BUG_REPORT,bugreport.zip,Pixel 8,2023-11-14T22:13:20Z,com.acme.field,com.acme.field:sync,CRASH," +
                "3,,2023-11-14T22:13:20Z,java.lang.IllegalStateException,boom,,at a.B.c(B.kt:1)",
            lines[1],
        )
        assertEquals("", lines[2])
    }

    @Test fun quotesCommasQuotesAndNewlines() {
        val out = csv(row("bad \"state\", retry", frames = "at a\nat b"))
        assertTrue(out.contains("\"bad \"\"state\"\", retry\""))
        assertTrue(out.contains("\"at a\nat b\""))
    }

    @Test fun neutralizesSpreadsheetFormulas() {
        assertTrue(csv(row("=HYPERLINK(\"x\")")).contains("\"'=HYPERLINK(\"\"x\"\")\""))
        assertTrue(csv(row("-1 left")).contains(",'-1 left,"))
    }

    @Test fun startsWithBom() {
        assertTrue(csv().startsWith("\uFEFF"))
    }
}
