package crcode.core

// The prompts are the same as in the VS Code extension, so both give the same answers.

private const val RULES =
    "You are helping a developer handle code review (CR) notes from a GitLab merge request. " +
        "You may read files in the repository for more context, but you must NOT modify, create or delete any file and must NOT run commands that change the repository."

private const val OUTDATED =
    "NOTE: the code at this line was changed after the review note was written. Check if the current code already addresses the note. If it does, return no edits (or say so in the reply)."

private const val FENCE = "```"

private fun conversation(t: CrThread): String =
    t.notes.joinToString("\n\n") { n -> "--- ${n.author}${n.createdAt?.let { " ($it)" } ?: ""}:\n${n.body}" }

private const val EDIT_RULES = """- Line numbers are 1-based and refer to the CURRENT file content. "startLine".."endLine" (inclusive) is replaced by "newText".
- "newText" is the full replacement for those lines, with correct indentation, without line-number prefixes and without a trailing newline.
- To insert without replacing, set "endLine" = "startLine" - 1 (the text is inserted before "startLine").
- To delete lines, use "newText": "".
- Keep the edit as small as possible. Edits must not overlap."""

fun fixPrompt(t: CrThread, numberedContext: String, contextStart: Int, contextEnd: Int): String = """$RULES

Task: propose a code change that addresses the review note below.

File: ${t.file}
Note is on line: ${t.line}
${if (t.outdated) OUTDATED + "\n" else ""}
Review conversation:
${conversation(t)}

Current content of ${t.file}, lines $contextStart-$contextEnd (format "<line>| <code>"):
$numberedContext

Answer with ONLY one JSON object in a ${FENCE}json fenced block, with this shape:
{
  "explanation": "short description of the change",
  "edits": [
    { "file": "${t.file}", "startLine": 10, "endLine": 12, "newText": "replacement code" }
  ]
}

Rules for "edits":
- "file" is relative to the repository root. Usually only ${t.file} needs to change.
$EDIT_RULES
- If the note does not need a code change (for example it is a question), return "edits": [] and explain why."""

fun replyPrompt(t: CrThread, codeContext: String?): String = """$RULES

Task: write a reply to the review conversation below, as the author of the merge request.
${if (t.file != null) "\nThe note is on ${t.location}." else ""}${if (t.outdated) "\n" + OUTDATED else ""}
${if (codeContext != null) "\nCode around the note:\n$codeContext\n" else ""}
Review conversation:
${conversation(t)}

The reply must be short, polite and specific. Answer questions directly. If the reviewer is right, agree and say what you will change. If you disagree, give the technical reason. Use GitLab markdown.

Answer with ONLY one JSON object in a ${FENCE}json fenced block: { "reply": "the reply text" }"""

fun selfReviewPrompt(baseRef: String, diff: String, truncated: Boolean): String = """$RULES

Task: do a careful code review of the changes below (git diff from $baseRef to the working tree), as a senior reviewer would before the merge request is approved.
Look for bugs, wrong logic, edge cases, security problems, race conditions, missing error handling, performance problems, missing tests and unclear code. Do not report pure formatting issues. Report only real issues; it is fine to return no findings.
${if (truncated) "\nNOTE: the diff was truncated because it is large. Review what is shown, and read files from the repository if you need more context.\n" else ""}
${FENCE}diff
$diff
$FENCE

Answer with ONLY one JSON object in a ${FENCE}json fenced block:
{
  "findings": [
    { "file": "path/relative/to/repo", "line": 42, "severity": "high|medium|low", "comment": "what is wrong and how to fix it" }
  ]
}
"line" is the 1-based line number in the NEW version of the file (the "+" side of the diff)."""

fun commitPrompt(f: CommitFormat, diff: String, stat: String, branch: String, recent: List<String>, truncated: Boolean): String {
    val lines = mutableListOf<String>()
    if (f.types.isNotEmpty()) {
        lines.add("- \"type\" must be one of: " + f.types.joinToString(", ") { if (it.description.isNotEmpty()) "${it.name} (${it.description})" else it.name } + ".")
    } else {
        lines.add("- \"type\": leave empty.")
    }
    if (f.scopes.isNotEmpty()) lines.add("- \"scope\" must be one of: ${f.scopes.joinToString(", ")}; or empty when none fits.")
    else lines.add("- \"scope\": the part of the code that changed (module, package, component), one or two words, or empty when the change is wide.")
    lines.add(
        "- \"subject\": one line, at most ${f.subjectMaxLength} characters, imperative mood (\"add\", not \"added\" or \"adds\"), " +
            when (f.subjectCase) {
                SubjectCase.LOWER -> "first letter in lower case"
                SubjectCase.SENTENCE -> "first letter in upper case"
                SubjectCase.ANY -> "any case"
            } + if (f.subjectPeriod) ", ends with a period." else ", no period at the end.",
    )
    if (f.body == BodyPolicy.NEVER) {
        lines.add("- \"body\": leave empty.")
    } else {
        lines.add(
            "- \"body\": ${if (f.body == BodyPolicy.ALWAYS) "required" else "only when the subject is not enough"}. Explain WHAT changed and WHY, not how. " +
                (if (f.bodyBullets) "Use a bullet list, one change per bullet, each starts with \"- \"." else "Use short paragraphs.") +
                " No markdown headings or code fences.",
        )
    }
    lines.add("- \"breaking\": a short description of the breaking change when the change breaks compatibility; otherwise empty.")
    lines.add("- Write in ${f.language.ifEmpty { "English" }}.")
    if (f.rules.isNotBlank()) lines.add("- Also follow these project rules:\n${f.rules.trim()}")
    val recentText = if (recent.isNotEmpty()) "\nRecent commit subjects of this repository (for the style, do not copy them):\n${recent.joinToString("\n") { "  $it" }}\n" else ""
    val examples = if (f.examples.isNotEmpty()) "\nExamples of good messages for this project:\n${f.examples.joinToString("\n") { "---\n$it" }}\n---\n" else ""
    return """You write git commit messages. You may read files in the repository for context, but you must NOT modify, create or delete any file and must NOT run commands that change the repository.

Task: write the commit message for the changes below.
Branch: $branch
$recentText$examples
Rules:
${lines.joinToString("\n")}

Changed files:
${stat.trim()}
${if (truncated) "\nNOTE: the diff was truncated because it is large. Describe what is shown; read files from the repository when you need more context.\n" else ""}
${FENCE}diff
$diff
$FENCE

Answer with ONLY one JSON object in a ${FENCE}json fenced block:
{ "type": "", "scope": "", "subject": "", "body": "", "breaking": "" }"""
}

fun pipelinePrompt(name: String, stage: String, failureReason: String?, log: String, truncated: Boolean, changedFiles: List<String>, baseRef: String): String = """$RULES

Task: a GitLab CI job failed on the merge request of the current branch. Find the cause from the job log and propose a code change that makes the job pass.

Job: "$name" (stage "$stage")${if (failureReason != null) ", failure reason reported by GitLab: $failureReason" else ""}
Files changed on this branch compared to $baseRef:
${if (changedFiles.isNotEmpty()) changedFiles.joinToString("\n") { "  $it" } else "  (none)"}

Job log${if (truncated) " (only the end is shown)" else ""}:
$FENCE
$log
$FENCE

First find the first real error in the log (not the follow-up errors). Read the related files in the repository to understand the problem.
Answer with ONLY one JSON object in a ${FENCE}json fenced block, with this shape:
{
  "cause": "one or two sentences: what failed and why",
  "explanation": "short description of the proposed change",
  "edits": [
    { "file": "path/relative/to/repo", "startLine": 10, "endLine": 12, "newText": "replacement code" }
  ]
}

Rules for "edits":
$EDIT_RULES
- When the failure is not caused by the code (for example a runner problem, a network error, or a missing secret), return "edits": [] and say so in "cause".
- Never make a test pass by removing or skipping it; fix the code it tests."""
