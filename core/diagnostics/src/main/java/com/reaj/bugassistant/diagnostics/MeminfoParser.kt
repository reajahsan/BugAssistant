package com.reaj.bugassistant.diagnostics

/** Parses `/proc/meminfo` into kB values keyed by field name (`MemTotal`, `SwapFree`, ...). */
object MeminfoParser {
    private val LINE = Regex("""^(\w+(?:\(\w+\))?):\s+(\d+)(?:\s+kB)?$""")

    fun parse(text: String): Map<String, Long> =
        text.lineSequence()
            .mapNotNull { LINE.matchEntire(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2].toLong() }
}
