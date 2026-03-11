package com.nettarion.hyperborea.platform.support

/**
 * Truncate exported log text to fit within [maxChars], preserving
 * the header, the first ~20% of log lines (startup context), and
 * the last ~80% (most recent activity). Inserts a marker showing
 * how many lines were omitted.
 */
fun truncateLogExport(exported: String, maxChars: Int): String {
    if (exported.length <= maxChars) return exported

    val lines = exported.lines()
    // Header = first 4 lines (title, exported timestamp, entry count, blank)
    val header = lines.take(4)
    val logLines = lines.drop(4)

    val headerText = header.joinToString("\n")
    val budget = maxChars - headerText.length - 1 // -1 for joining newline

    val headBudget = budget / 5          // 20% for startup context
    val tailBudget = budget * 4 / 5 - 60 // 80% minus room for marker

    // Take lines from the start
    val headLines = mutableListOf<String>()
    var headUsed = 0
    for (line in logLines) {
        val cost = line.length + 1
        if (headUsed + cost > headBudget) break
        headLines.add(line)
        headUsed += cost
    }

    // Take lines from the end
    val tailLines = mutableListOf<String>()
    var tailUsed = 0
    for (line in logLines.asReversed()) {
        val cost = line.length + 1
        if (tailUsed + cost > tailBudget) break
        tailLines.add(0, line)
        tailUsed += cost
    }

    val omitted = logLines.size - headLines.size - tailLines.size
    val marker = "--- $omitted entries omitted ---"

    return (header + headLines + listOf("", marker, "") + tailLines)
        .joinToString("\n")
}
