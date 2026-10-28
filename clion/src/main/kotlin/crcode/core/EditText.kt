package crcode.core

// Edit logic on plain text. IntelliJ documents always use "\n" inside, so no line-ending handling is needed here.

class EditException(message: String) : Exception(message)

private fun lineStarts(text: String): IntArray {
    val starts = mutableListOf(0)
    text.forEachIndexed { i, c -> if (c == '\n') starts.add(i + 1) }
    return starts.toIntArray()
}

private fun lineCount(text: String) = lineStarts(text).size

private fun lineText(text: String, starts: IntArray, line0: Int): String {
    val end = if (line0 + 1 < starts.size) starts[line0 + 1] - 1 else text.length
    return text.substring(starts[line0], end)
}

/** [start, end) offsets of lines startLine..endLine (1-based, inclusive), with the line break after endLine when there is one. */
private fun lineRange(text: String, starts: IntArray, startLine: Int, endLine: Int): OffsetRange {
    val start = starts[startLine - 1]
    val end = if (endLine < starts.size) starts[endLine] else text.length
    return OffsetRange(start, end)
}

/** Turns a line-based edit from opencode into an exact region of the current text. */
fun prepareEdit(text: String, e: TextEdit): PreparedEdit {
    val newText = e.newText.replace("\r\n", "\n")
    val starts = lineStarts(text)
    val count = starts.size
    if (e.endLine == e.startLine - 1) {
        if (e.startLine > count + 1) throw EditException("${e.file}: cannot insert at line ${e.startLine}, the file has $count lines")
        if (e.startLine == count + 1) return PreparedEdit(e.file, e.startLine, e.endLine, newText, "", "\n" + newText)
        return PreparedEdit(e.file, e.startLine, e.endLine, newText, "", newText + "\n", lineText(text, starts, e.startLine - 1))
    }
    if (e.endLine > count) throw EditException("${e.file}: line ${e.endLine} is past the end of the file ($count lines)")
    val r = lineRange(text, starts, e.startLine, e.endLine)
    val oldText = text.substring(r.start, r.end)
    val endsWithEol = e.endLine < count
    val replacement = if (endsWithEol && newText.isNotEmpty()) newText + "\n" else newText
    return PreparedEdit(e.file, e.startLine, e.endLine, newText, oldText, replacement)
}

/** Finds the region of a prepared edit in the text as it is now. */
fun locate(text: String, e: PreparedEdit): OffsetRange {
    val starts = lineStarts(text)
    if (e.oldText.isEmpty()) {
        if (e.anchorLine == null) return OffsetRange(text.length, text.length)
        if (e.startLine - 1 < starts.size && lineText(text, starts, e.startLine - 1) == e.anchorLine) {
            return OffsetRange(starts[e.startLine - 1], starts[e.startLine - 1])
        }
        throw EditException("${e.file}: the code near line ${e.startLine} changed. Ask for a new suggestion.")
    }
    if (e.endLine <= starts.size) {
        val r = lineRange(text, starts, e.startLine, e.endLine)
        if (text.substring(r.start, r.end) == e.oldText) return r
    }
    val first = text.indexOf(e.oldText)
    if (first != -1 && text.indexOf(e.oldText, first + 1) == -1) return OffsetRange(first, first + e.oldText.length)
    throw EditException("${e.file}: the code at lines ${e.startLine}-${e.endLine} changed. Ask for a new suggestion.")
}

/** The text after all edits of one file, without changing anything. */
fun previewText(text: String, edits: List<PreparedEdit>): String {
    val located = edits.map { it to locate(text, it) }
    if (findOverlaps(located.map { it.second }).isNotEmpty()) throw EditException("${edits.first().file}: the edits of this fix overlap")
    var out = text
    for ((e, r) in located.sortedByDescending { it.second.start }) {
        out = out.substring(0, r.start) + e.replacement + out.substring(r.end)
    }
    return out
}

@Suppress("unused")
fun countLines(text: String) = lineCount(text)
