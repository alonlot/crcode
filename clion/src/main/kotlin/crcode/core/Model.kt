package crcode.core

data class CrNote(
    val id: Long? = null,
    val authorId: Long? = null,
    val author: String,
    val avatarUrl: String? = null,
    val body: String,
    val createdAt: String? = null,
)

/**
 * A text edit resolved against the document when the suggestion was made.
 * `oldText` is the exact text of the replaced region and `replacement` the exact text that goes in,
 * so the edit can be found again after the document changes.
 */
data class PreparedEdit(
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val newText: String,
    val oldText: String,
    val replacement: String,
    /** For pure inserts: the text of the line that followed the insert point. */
    val anchorLine: String? = null,
)

data class FixSuggestion(val explanation: String, val edits: List<PreparedEdit>)

enum class ThreadSource { GITLAB, SELF, PIPELINE }

class CrThread(
    val id: String,
    val source: ThreadSource,
    val notes: MutableList<CrNote>,
    /** Path relative to the repository root, with `/`. */
    val file: String?,
    /** 1-based line in the current version of the file. */
    var line: Int?,
    var resolvable: Boolean = false,
    var resolved: Boolean = false,
    val discussionId: String? = null,
    val severity: String? = null,
) {
    var suggestion: FixSuggestion? = null
    var draftReply: String? = null
    /** Set when the user applied or discarded the fix, so the automatic run and Fix All do not make it again. */
    var skipAutoFix = false
    /** Set when the user discarded the reply draft. */
    var skipAutoReply = false
    /** Commit the note's line number refers to (GitLab position head_sha). */
    var positionSha: String? = null
    /** Line number on GitLab, before it was moved to the local file. */
    var gitlabLine: Int? = null
    /** The line of the note was changed or deleted locally after the note was written. */
    var outdated = false
    /** For a CI failure: the job name and URL. */
    var subtitle: String? = null
    var jobUrl: String? = null

    val title: String get() = plainTitle(notes.firstOrNull()?.body ?: "")

    /** Changes when a new note is added, so cached suggestions for older conversations are not reused. */
    val cacheKey: String get() = "$id:${notes.lastOrNull()?.id ?: notes.size}"

    val location: String get() = if (file != null) file + (line?.let { ":$it" } ?: "") else "General"

    /** True when nothing shown for this thread changed between two loads. */
    fun sameAs(o: CrThread): Boolean =
        cacheKey == o.cacheKey && file == o.file && line == o.line && outdated == o.outdated &&
            resolved == o.resolved && resolvable == o.resolvable

    val label: String
        get() = when (source) {
            ThreadSource.SELF -> "Self review" + (severity?.let { " · $it" } ?: "")
            ThreadSource.PIPELINE -> "CI failure" + (subtitle?.let { " · $it" } ?: "")
            ThreadSource.GITLAB -> if (outdated) "GitLab CR · outdated (the code changed, was line $gitlabLine)" else "GitLab CR"
        }
}
