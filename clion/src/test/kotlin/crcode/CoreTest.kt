package crcode

import com.google.gson.JsonParser
import crcode.core.BodyPolicy
import crcode.core.CiJobLike
import crcode.core.CommitFormat
import crcode.core.CommitLayout
import crcode.core.CommitParts
import crcode.core.CommitType
import crcode.core.Hunk
import crcode.core.JobAction
import crcode.core.OffsetRange
import crcode.core.SubjectCase
import crcode.core.TextEdit
import crcode.core.checkCommitParts
import crcode.core.cleanJobLog
import crcode.core.extractIssue
import crcode.core.extractJson
import crcode.core.findOverlaps
import crcode.core.formatDuration
import crcode.core.groupStages
import crcode.core.jobActions
import crcode.core.locate
import crcode.core.mapLine
import crcode.core.normalizeSubject
import crcode.core.numberLines
import crcode.core.parseHunks
import crcode.core.parseOpencodeEvents
import crcode.core.parseRemote
import crcode.core.parseTypeEntry
import crcode.core.prepareEdit
import crcode.core.previewText
import crcode.core.renderCommitMessage
import crcode.core.renderTemplate
import crcode.core.stageStatus
import crcode.core.stripBasePath
import crcode.core.validateFixAnswer
import crcode.core.wrapText
import crcode.settings.DEFAULT_TEMPLATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test
    fun parseRemoteForms() {
        assertEquals("grp/sub/proj", parseRemote("git@gitlab.example.com:grp/sub/proj.git")!!.path)
        assertEquals("https://gitlab.example.com", parseRemote("git@gitlab.example.com:grp/proj.git")!!.guessedBaseUrl)
        val ssh = parseRemote("ssh://git@gitlab.example.com:2222/grp/proj.git")!!
        assertEquals("grp/proj", ssh.path)
        assertEquals("https://gitlab.example.com", ssh.guessedBaseUrl)
        val http = parseRemote("http://127.0.0.1:18765/grp/proj.git")!!
        assertEquals("http://127.0.0.1:18765", http.guessedBaseUrl)
        assertEquals("grp/proj", http.path)
        assertEquals("grp/proj", stripBasePath("gitlab/grp/proj", "https://example.com/gitlab"))
    }

    @Test
    fun opencodeEventsAndJson() {
        val out = parseOpencodeEvents(
            """{"type":"step_start","part":{}}
{"type":"text","part":{"type":"text","text":"Here:\n```json\n{\"a\":1}\n```"}}
not json
{"type":"error","error":{"name":"X","data":{"message":"quota"}}}""",
        )
        assertEquals(listOf("quota"), out.errors)
        assertEquals(1, extractJson(out.text).asJsonObject.get("a").asInt)
        assertEquals(2, extractJson("text {\"b\": 2} more").asJsonObject.get("b").asInt)
        assertEquals(3, extractJson("```\n{\"c\":3}\n```\n```json\n{\"c\":4}\n```").asJsonObject.get("c").asInt.minus(1))
        assertThrows(Exception::class.java) { extractJson("no json") }
    }

    @Test
    fun fixAnswerValidation() {
        val a = validateFixAnswer(JsonParser.parseString("""{"explanation":"x","edits":[{"file":"a","startLine":"2","newText":"y"}]}"""))
        assertEquals(TextEdit("a", 2, 2, "y"), a.edits[0])
        assertThrows(Exception::class.java) { validateFixAnswer(JsonParser.parseString("""{"edits":[{"file":"a","startLine":3,"endLine":1}]}""")) }
        assertThrows(Exception::class.java) { validateFixAnswer(JsonParser.parseString("""{"explanation":"no edits"}""")) }
    }

    @Test
    fun overlapsAndNumbering() {
        assertEquals(setOf(1), findOverlaps(listOf(OffsetRange(0, 5), OffsetRange(3, 8), OffsetRange(8, 9))))
        assertEquals(" 9| a\n10| b", numberLines(listOf("a", "b"), 9))
    }

    @Test
    fun lineMappingMatchesGit() {
        // 2 lines added after line 1, line 5 changed, lines 10-11 deleted (git gives +c,0 with c = line before).
        val hunks = parseHunks("@@ -1,0 +2,2 @@\n+x\n+y\n@@ -5 +7 @@\n-a\n+b\n@@ -10,2 +11,0 @@\n-c\n-d\n")
        assertEquals(listOf(Hunk(1, 0, 2, 2), Hunk(5, 1, 7, 1), Hunk(10, 2, 11, 0)), hunks)
        assertEquals(1 to false, mapLine(hunks, 1).let { it.line to it.outdated })
        assertEquals(4 to false, mapLine(hunks, 2).let { it.line to it.outdated })
        assertEquals(7 to true, mapLine(hunks, 5).let { it.line to it.outdated })
        assertEquals(12 to true, mapLine(hunks, 10).let { it.line to it.outdated })
        assertEquals(12 to false, mapLine(hunks, 12).let { it.line to it.outdated })
    }

    @Test
    fun editsOnText() {
        val text = "a\nb\nc\n"
        val replace = prepareEdit(text, TextEdit("f", 2, 2, "B"))
        assertEquals("a\nB\nc\n", previewText(text, listOf(replace)))
        val insert = prepareEdit(text, TextEdit("f", 2, 1, "x"))
        assertEquals("a\nx\nb\nc\n", previewText(text, listOf(insert)))
        val delete = prepareEdit(text, TextEdit("f", 1, 1, ""))
        assertEquals("b\nc\n", previewText(text, listOf(delete)))
        // The edit is found again after lines were added above it.
        assertEquals(OffsetRange(6, 8), locate("new\na\nb\nc\n", replace))
        assertThrows(Exception::class.java) { locate("a\nchanged\nc\n", replace) }
        assertThrows(Exception::class.java) { previewText(text, listOf(replace, prepareEdit(text, TextEdit("f", 1, 2, "z")))) }
    }

    @Test
    fun templates() {
        val all = mapOf("type" to "feat", "scope" to "api", "subject" to "add login", "body" to "Why.", "breaking" to "token format", "footer" to "Refs: #12")
        assertEquals("feat(api)!: add login\n\nWhy.\n\nBREAKING CHANGE: token format\n\nRefs: #12", renderTemplate(DEFAULT_TEMPLATE, all))
        assertEquals("fix: typo", renderTemplate(DEFAULT_TEMPLATE, mapOf("type" to "fix", "subject" to "typo")))
        assertEquals("no type", renderTemplate(DEFAULT_TEMPLATE, mapOf("subject" to "no type")))
        assertEquals("s {json}", renderTemplate("{subject} {{json}} {unknown}", mapOf("subject" to "s")))
        assertEquals("ac", renderTemplate("{?a:{?b:both}{?c:ac}}", mapOf("a" to "1", "c" to "1")))
    }

    @Test
    fun wrapAndSubject() {
        assertEquals("one two\nthree\nfour five", wrapText("one two three four five", 9))
        assertEquals("- one two\n  three\n  four", wrapText("- one two three four", 10))
        assertEquals("https://example.com/a/very/long/url", wrapText("https://example.com/a/very/long/url", 10))
        assertEquals("add login", normalizeSubject("Add  login.", SubjectCase.LOWER, false))
        assertEquals("Add login.", normalizeSubject("add login", SubjectCase.SENTENCE, true))
        assertEquals("JSON parser fix", normalizeSubject("JSON parser fix", SubjectCase.LOWER, false))
        val pattern = "(?:^|[/_-])([A-Z]+-\\d+|\\d+)(?=[/_-]|$)"
        assertEquals("123", extractIssue("123-fix-login", pattern))
        assertEquals("ABC-42", extractIssue("feature/ABC-42-login", pattern))
        assertEquals("", extractIssue("main", pattern))
        assertEquals(CommitType("feat", "a new feature"), parseTypeEntry("feat: a new feature"))
    }

    private val format = CommitFormat(
        listOf(CommitType("feat", ""), CommitType("fix", "")), listOf("api", "ui"), 20, SubjectCase.LOWER, false,
        BodyPolicy.WHEN_USEFUL, false, "English", "", emptyList(),
    )

    @Test
    fun commitChecksAndRender() {
        val ok = checkCommitParts(JsonParser.parseString("""{"type":"Fix","scope":"API","subject":"Handle null.","body":"b"}"""), format)
        assertEquals(emptyList<String>(), ok.problems)
        assertEquals(CommitParts("fix", "api", "handle null", "b", ""), ok.parts)
        val bad = checkCommitParts(JsonParser.parseString("""{"type":"chore","scope":"db","subject":"a very long subject that is too long"}"""), format)
        assertEquals(3, bad.problems.size)
        val msg = renderCommitMessage(
            CommitParts("fix", "", "handle null", "one two three four five six", ""),
            CommitLayout(DEFAULT_TEMPLATE, "{?issue:Refs: #{issue}}{?mr: !{mr}}", "(\\d+)", 12),
            "42-null",
            7,
        )
        assertEquals("fix: handle null\n\none two\nthree four\nfive six\n\nRefs: #42 !7", msg)
    }

    @Test
    fun jobLogCleanup() {
        val raw = "\u001b[0KRunning with gitlab-runner\r\n\u001b[0Ksection_start:1700000000:step_script\r\u001b[0K\u001b[32;1m$ npm test\u001b[0;m\r\nprogress 10%\rprogress 100%\r\nFAIL src/a.test.ts\nsection_end:1700000001:step_script\r\u001b[0K\n"
        val out = cleanJobLog(raw, 10_000)
        assertEquals("Running with gitlab-runner\n$ npm test\nprogress 100%\nFAIL src/a.test.ts\n\n", out.text)
        val tail = cleanJobLog("line1\nline2\nline3\n", 9)
        assertTrue(tail.truncated)
        assertEquals("line3\n", tail.text)
    }

    private data class J(override val id: Long, override val stage: String, override val status: String, override val allowFailure: Boolean = false) : CiJobLike {
        override val name = "j$id"
    }

    @Test
    fun ciRules() {
        val stages = groupStages(listOf(J(1, "build", "success"), J(2, "test", "failed", true), J(3, "test", "success"), J(4, "deploy", "manual"), J(5, "build", "success")))
        assertEquals(listOf(Triple("build", "success", 2), Triple("test", "warning", 2), Triple("deploy", "manual", 1)), stages.map { Triple(it.name, it.status, it.jobs.size) })
        assertEquals("running", stageStatus(listOf(J(1, "s", "success"), J(2, "s", "running"))))
        assertEquals("pending", stageStatus(listOf(J(1, "s", "failed"), J(2, "s", "pending"))))
        assertEquals("failed", stageStatus(listOf(J(1, "s", "failed"), J(2, "s", "success"))))
        assertEquals("skipped", stageStatus(listOf(J(1, "s", "skipped"))))
        assertEquals("2m 5s", formatDuration(125.0))
        assertEquals("1h 1m", formatDuration(3700.0))
        assertEquals("", formatDuration(null))
        assertEquals(listOf(JobAction.PLAY), jobActions("manual"))
        assertEquals(listOf(JobAction.CANCEL), jobActions("running"))
    }
}
