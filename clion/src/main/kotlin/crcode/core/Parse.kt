package crcode.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

// Pure helpers with no dependency on the IDE, so they can be unit tested.

data class OpencodeOutput(val text: String, val errors: List<String>)

/** Collects assistant text and errors from `opencode run --format json` output (one JSON event per line). */
fun parseOpencodeEvents(stdout: String): OpencodeOutput {
    val texts = mutableListOf<String>()
    val errors = mutableListOf<String>()
    for (line in stdout.lines()) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) continue
        val event = try {
            JsonParser.parseString(trimmed).asJsonObject
        } catch (_: Exception) {
            continue
        }
        when (event.str("type")) {
            "text" -> event.obj("part")?.str("text")?.let { texts.add(it) }
            "error" -> {
                val e = event.obj("error") ?: JsonObject()
                errors.add(e.obj("data")?.str("message") ?: e.str("message") ?: e.str("name") ?: e.toString())
            }
        }
    }
    return OpencodeOutput(texts.joinToString("\n"), errors)
}

class OpencodeAnswerError(message: String) : Exception(message)

/**
 * Finds the JSON value in a model answer. Prefers the last ```json fenced block,
 * then any fenced block, then the first balanced {...} object in the text.
 */
fun extractJson(text: String): JsonElement {
    val fences = Regex("```(json)?\\s*\\n([\\s\\S]*?)```").findAll(text).toList()
    val candidates = mutableListOf<String>()
    fences.filter { it.groupValues[1].isNotEmpty() }.reversed().forEach { candidates.add(it.groupValues[2]) }
    fences.filter { it.groupValues[1].isEmpty() }.reversed().forEach { candidates.add(it.groupValues[2]) }
    firstBalancedObject(text)?.let { candidates.add(it) }
    for (c in candidates) {
        try {
            val parsed = JsonParser.parseString(c.trim())
            if (parsed.isJsonObject || parsed.isJsonArray) return parsed
        } catch (_: Exception) {
            // try next candidate
        }
    }
    throw OpencodeAnswerError("opencode did not return valid JSON. Answer was:\n" + text.take(1000))
}

private fun firstBalancedObject(text: String): String? {
    var start = text.indexOf('{')
    while (start != -1) {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
            } else if (ch == '"') {
                inString = true
            } else if (ch == '{') {
                depth++
            } else if (ch == '}') {
                depth--
                if (depth == 0) {
                    val candidate = text.substring(start, i + 1)
                    try {
                        JsonParser.parseString(candidate)
                        return candidate
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            i++
        }
        start = text.indexOf('{', start + 1)
    }
    return null
}

/** Prefixes each line with its 1-based number, as context for the model. */
fun numberLines(lines: List<String>, firstLine: Int): String {
    val width = (firstLine + lines.size - 1).toString().length
    return lines.mapIndexed { i, l -> "${(firstLine + i).toString().padStart(width)}| $l" }.joinToString("\n")
}

data class TextEdit(val file: String, val startLine: Int, val endLine: Int, val newText: String)

data class FixAnswer(val explanation: String, val edits: List<TextEdit>)

fun validateFixAnswer(raw: JsonElement?): FixAnswer {
    val obj = raw?.takeIf { it.isJsonObject }?.asJsonObject
    val edits = obj?.get("edits")?.takeIf { it.isJsonArray }?.asJsonArray
        ?: throw OpencodeAnswerError("opencode answer has no \"edits\" array")
    val list = edits.mapIndexed { i, el ->
        val e = el.takeIf { it.isJsonObject }?.asJsonObject ?: throw OpencodeAnswerError("Edit ${i + 1} is not an object")
        val file = e.str("file")
        val startLine = e.int("startLine")
        val endLine = if (e.has("endLine") && !e.get("endLine").isJsonNull) e.int("endLine") else startLine
        if (file == null || startLine == null || endLine == null) {
            throw OpencodeAnswerError("Edit ${i + 1} must have \"file\", \"startLine\" and \"endLine\"")
        }
        if (startLine < 1 || endLine < startLine - 1) {
            throw OpencodeAnswerError("Edit ${i + 1} has an invalid line range $startLine-$endLine")
        }
        TextEdit(file, startLine, endLine, e.str("newText") ?: "")
    }
    return FixAnswer(obj.str("explanation") ?: "", list)
}

/** Builds a small unified-style diff for display. */
fun renderDiff(oldText: String, newText: String, startLine: Int): String {
    val out = mutableListOf("@@ line $startLine @@")
    if (oldText.isNotEmpty()) oldText.split("\n").forEach { out.add("- $it") }
    if (newText.isNotEmpty()) newText.split("\n").forEach { out.add("+ $it") }
    return out.joinToString("\n")
}

data class OffsetRange(val start: Int, val end: Int)

/** Ranges are [start, end) offsets. Returns the indexes of items that overlap an earlier accepted item. */
fun findOverlaps(ranges: List<OffsetRange>): Set<Int> {
    val order = ranges.withIndex().sortedWith(compareBy({ it.value.start }, { it.value.end }))
    val rejected = mutableSetOf<Int>()
    var lastEnd = -1
    for ((i, r) in order) {
        if (r.start < lastEnd) rejected.add(i) else lastEnd = maxOf(lastEnd, r.end)
    }
    return rejected
}

data class Hunk(val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int)

/** Reads the hunk headers of a `git diff --unified=0` output. */
fun parseHunks(diff: String): List<Hunk> =
    Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@", RegexOption.MULTILINE).findAll(diff).map { m ->
        Hunk(
            m.groupValues[1].toInt(),
            m.groupValues[2].ifEmpty { "1" }.toInt(),
            m.groupValues[3].toInt(),
            m.groupValues[4].ifEmpty { "1" }.toInt(),
        )
    }.toList()

data class MappedLine(val line: Int, val outdated: Boolean)

/**
 * Moves a line number of the old file to the new file through the hunks of a `--unified=0` diff.
 * `outdated` is true when the line itself was changed or deleted; the line is then the nearest line.
 */
fun mapLine(hunks: List<Hunk>, oldLine: Int): MappedLine {
    var delta = 0
    for (h in hunks) {
        if (h.oldCount == 0) {
            if (h.oldStart < oldLine) delta += h.newCount
            continue
        }
        val oldEnd = h.oldStart + h.oldCount - 1
        if (oldLine > oldEnd) {
            delta += h.newCount - h.oldCount
        } else if (oldLine >= h.oldStart) {
            return MappedLine(maxOf(1, if (h.newCount == 0) h.newStart + 1 else h.newStart), true)
        }
    }
    return MappedLine(maxOf(1, oldLine + delta), false)
}

// ---------- small Gson helpers ----------

fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonObject.int(key: String): Int? {
    val p = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    val d = when {
        p.isNumber -> p.asDouble
        p.isString -> p.asString.trim().toDoubleOrNull()
        else -> null
    } ?: return null
    return if (d % 1.0 == 0.0) d.toInt() else null
}
