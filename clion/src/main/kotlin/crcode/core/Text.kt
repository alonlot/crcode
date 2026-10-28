package crcode.core

import com.google.gson.JsonElement

/**
 * Fills a commit message template.
 *  - `{name}` is replaced by the field value (empty when the field is missing).
 *  - `{?name:...}` keeps the text between `:` and the matching `}` only when the field is not empty.
 *    The text can contain other placeholders and conditional blocks.
 *  - `{{` and `}}` give literal braces.
 */
fun renderTemplate(template: String, fields: Map<String, String?>): String {
    fun value(name: String) = (fields[name] ?: "").trim()
    fun render(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val next = s.getOrNull(i + 1)
            when {
                ch == '{' && next == '{' -> { out.append('{'); i++ }
                ch == '}' && next == '}' -> { out.append('}'); i++ }
                ch == '{' && next == '?' -> {
                    val colon = s.indexOf(':', i + 2)
                    val close = matchingBrace(s, i)
                    if (colon == -1 || close == -1 || colon > close) {
                        out.append(ch)
                    } else {
                        if (value(s.substring(i + 2, colon).trim()).isNotEmpty()) out.append(render(s.substring(colon + 1, close)))
                        i = close
                    }
                }
                ch == '{' -> {
                    val close = s.indexOf('}', i)
                    val name = if (close == -1) "" else s.substring(i + 1, close).trim()
                    if (close != -1 && Regex("^[\\w.-]+$").matches(name)) {
                        out.append(value(name))
                        i = close
                    } else {
                        out.append(ch)
                    }
                }
                else -> out.append(ch)
            }
            i++
        }
        return out.toString()
    }
    return render(template)
        .replace(Regex("[ \\t]+$", RegexOption.MULTILINE), "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

private fun matchingBrace(s: String, open: Int): Int {
    var depth = 0
    for (i in open until s.length) {
        if (s[i] == '{') depth++
        else if (s[i] == '}') {
            depth--
            if (depth == 0) return i
        }
    }
    return -1
}

/** Wraps paragraphs at `width` columns. Bullet lines keep their indent; lines without spaces (URLs) are not cut. */
fun wrapText(text: String, width: Int): String {
    if (width <= 0) return text
    return text.split("\n").joinToString("\n") { line ->
        if (line.length <= width) return@joinToString line
        val bullet = Regex("^(\\s*(?:[-*+]|\\d+[.)])\\s+)").find(line)?.groupValues?.get(1)
        val indent = if (bullet != null) " ".repeat(bullet.length) else Regex("^\\s*").find(line)!!.value
        val words = line.trim().split(Regex("\\s+"))
        val skip = if (bullet != null) bullet.trim().split(Regex("\\s+")).size else 0
        val out = mutableListOf<String>()
        var current = bullet ?: indent
        var hasWord = false
        for (w in words.drop(skip)) {
            if (hasWord && current.length + 1 + w.length > width) {
                out.add(current)
                current = indent + w
            } else {
                current += (if (hasWord) " " else "") + w
            }
            hasWord = true
        }
        out.add(current)
        out.joinToString("\n")
    }
}

enum class SubjectCase { LOWER, SENTENCE, ANY }

/** Applies the subject rules of the commit format: case of the first letter and no final period. */
fun normalizeSubject(subject: String, case: SubjectCase, period: Boolean): String {
    var s = subject.trim().replace(Regex("\\s+"), " ")
    if (case == SubjectCase.LOWER && Regex("^[A-Z][a-z]").containsMatchIn(s)) {
        s = s[0].lowercaseChar() + s.substring(1)
    } else if (case == SubjectCase.SENTENCE && Regex("^[a-z]").containsMatchIn(s)) {
        s = s[0].uppercaseChar() + s.substring(1)
    }
    s = if (!period) s.replace(Regex("\\.+$"), "") else if (!Regex("[.!?]$").containsMatchIn(s)) "$s." else s
    return s
}

/** Finds an issue reference in a branch name, for example `123-fix-login` -> `123` or `feat/ABC-42` -> `ABC-42`. */
fun extractIssue(branch: String, pattern: String): String {
    if (pattern.isEmpty()) return ""
    return try {
        val m = Regex(pattern).find(branch) ?: return ""
        m.groups[1]?.value ?: m.value
    } catch (_: Exception) {
        ""
    }
}

data class CommitType(val name: String, val description: String)

/** Reads a commit type setting entry such as `feat: a new feature` into its name and description. */
fun parseTypeEntry(entry: String): CommitType {
    val i = entry.indexOf(':')
    return if (i == -1) CommitType(entry.trim(), "") else CommitType(entry.substring(0, i).trim(), entry.substring(i + 1).trim())
}

data class CleanLog(val text: String, val truncated: Boolean)

/**
 * Removes colors, cursor movement, GitLab section markers and progress-bar rewrites from a CI job log,
 * then keeps only the last `maxBytes` bytes (from the start of a line).
 */
fun cleanJobLog(raw: String, maxBytes: Int): CleanLog {
    val text = raw
        .replace(Regex("\u001b\\[[0-9;?]*[A-Za-z]"), "")
        .replace(Regex("\u001b\\][^\u0007]*\u0007"), "")
        .replace(Regex("section_(?:start|end):\\d+:[\\w.-]+(?:\\[[^\\]]*\\])?\r?"), "")
        .replace("\r\n", "\n")
        .split("\n")
        .joinToString("\n") { it.split("\r").last() }
        .replace(Regex("\\n{3,}"), "\n\n")
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return CleanLog(text, false)
    var tail = String(bytes, bytes.size - maxBytes, maxBytes, Charsets.UTF_8)
    val nl = tail.indexOf('\n')
    if (nl != -1 && nl < tail.length - 1) tail = tail.substring(nl + 1)
    return CleanLog(tail, true)
}

enum class BodyPolicy { NEVER, WHEN_USEFUL, ALWAYS }

data class CommitFormat(
    val types: List<CommitType>,
    val scopes: List<String>,
    val subjectMaxLength: Int,
    val subjectCase: SubjectCase,
    val subjectPeriod: Boolean,
    val body: BodyPolicy,
    val bodyBullets: Boolean,
    val language: String,
    val rules: String,
    val examples: List<String>,
)

data class CommitParts(val type: String, val scope: String, val subject: String, val body: String, val breaking: String)

data class CheckedCommit(val parts: CommitParts, val problems: List<String>)

/** Checks the model answer against the format. Returns the problems, so the model can be asked again. */
fun checkCommitParts(raw: JsonElement?, f: CommitFormat): CheckedCommit {
    val o = raw?.takeIf { it.isJsonObject }?.asJsonObject
    fun s(k: String) = (o?.str(k) ?: "").trim()
    var type = s("type")
    var scope = s("scope")
    val subject = normalizeSubject(s("subject"), f.subjectCase, f.subjectPeriod)
    var body = s("body")
    val breaking = s("breaking")
    val problems = mutableListOf<String>()
    if (subject.isEmpty()) problems.add("\"subject\" is empty")
    else if (subject.length > f.subjectMaxLength) problems.add("\"subject\" has ${subject.length} characters, the limit is ${f.subjectMaxLength}")
    if (f.types.isNotEmpty()) {
        val names = f.types.map { it.name }
        val match = names.find { it.equals(type, ignoreCase = true) }
        if (match != null) type = match
        else problems.add("\"type\" is \"$type\", it must be one of ${names.joinToString(", ")}")
    } else {
        type = ""
    }
    if (f.scopes.isNotEmpty() && scope.isNotEmpty()) {
        val match = f.scopes.find { it.equals(scope, ignoreCase = true) }
        if (match != null) scope = match
        else problems.add("\"scope\" is \"$scope\", it must be one of ${f.scopes.joinToString(", ")} or empty")
    }
    if (f.body == BodyPolicy.NEVER) body = ""
    else if (f.body == BodyPolicy.ALWAYS && body.isEmpty()) problems.add("\"body\" is empty, it is required")
    return CheckedCommit(CommitParts(type, scope, subject, body, breaking), problems)
}

data class CommitLayout(val template: String, val footer: String, val issuePattern: String, val bodyWrap: Int)

/** Builds the final message from the parts and the template. */
fun renderCommitMessage(parts: CommitParts, layout: CommitLayout, branch: String, mr: Int?): String {
    val base = mapOf(
        "type" to parts.type,
        "scope" to parts.scope,
        "subject" to parts.subject,
        "body" to wrapText(parts.body, layout.bodyWrap),
        "breaking" to wrapText(parts.breaking, layout.bodyWrap),
        "branch" to branch,
        "issue" to extractIssue(branch, layout.issuePattern),
        "mr" to (mr?.toString() ?: ""),
    )
    val footer = renderTemplate(layout.footer, base)
    return renderTemplate(layout.template, base + ("footer" to footer))
}

/** Strips common markdown marks for one-line titles. */
fun plainTitle(body: String): String {
    val line = body.lines().firstOrNull { it.isNotBlank() } ?: ""
    return line.replace(Regex("\\*\\*|__|`"), "").trim().take(120).ifEmpty { "(empty)" }
}
