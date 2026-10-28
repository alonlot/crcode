package crcode

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import crcode.core.ThreadSource
import crcode.git.Git
import crcode.service.CrService
import crcode.settings.CrProjectSettings
import crcode.settings.CrSettings
import crcode.settings.Tokens
import crcode.ui.EditorMarkers
import crcode.ui.NotesPanel
import crcode.ui.PipelinePanel
import crcode.ui.PipelineWidget
import java.awt.Component
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * Runs the plugin in the headless IDE test platform against a mock GitLab and a fake opencode:
 * load, line mapping, automatic suggestions, apply, reply, pipeline, CI analysis, commit message, and the UI.
 */
class PluginIntegrationTest : HeavyPlatformTestCase() {
    private lateinit var mock: MockGitLab
    private lateinit var root: String
    private lateinit var ocLog: File
    private val shots = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }

    private fun git(vararg args: String) = Git.run(root, *args)

    override fun setUp() {
        super.setUp()
        root = project.basePath!!
        File(root).mkdirs()
        File(root, "src").mkdirs()
        File(root, "src/a.ts").writeText(listOf("// a", "const v2 = 1;", "const v3 = obj.x;", "// four", "const v5 = 5;", "").joinToString("\n"))
        git("init", "-q", "-b", "main")
        git("config", "user.email", "t@t")
        git("config", "user.name", "t")
        File(root, ".gitignore").writeText(".idea/\n*.iml\n*.ipr\n*.iws\n")
        git("add", ".")
        git("commit", "-qm", "base")
        git("checkout", "-qb", "12-feature")
        File(root, "src/a.ts").appendText("const added = 6;\n")
        git("commit", "-qam", "change")
        val sha = git("rev-parse", "HEAD").trim()
        mock = MockGitLab(sha).start()
        git("remote", "add", "origin", "http://127.0.0.1:${mock.port}/grp/proj.git")
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(root))?.refresh(false, true)

        // Fake opencode: a shell wrapper that runs the Java single-file program with the test JVM.
        ocLog = File.createTempFile("opencode-calls", ".log")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val src = File(javaClass.getResource("/FakeOpencode.java")!!.toURI()).path
        val wrapper = File.createTempFile("opencode", ".sh")
        wrapper.writeText("#!/bin/sh\nexport FAKE_OC_LOG='${ocLog.path}'\nexec '$java' '$src' \"$@\"\n")
        wrapper.setExecutable(true)

        val app = CrSettings.app().state
        app.opencodePath = wrapper.path
        app.pollMinutes = 0
        app.suggestFixes = true
        app.suggestReplies = true
        app.confirmFixAll = false
        app.pipelineAutoExplain = false
        app.pipelineNotify = false
        val ps = CrProjectSettings.of(project).state
        ps.commitFooter = "{?issue:Refs: #{issue}}{?mr: (see !{mr})}"
        ps.commitIssuePattern = "(\\d+)"
        ps.commitBodyWrap = 40
        Tokens.set("http://127.0.0.1:${mock.port}", "tok")
    }

    override fun tearDown() {
        try {
            // CLion's bundled plugins turn this option on at startup; the framework check expects the default.
            com.intellij.codeInsight.CodeInsightSettings.getInstance().AUTO_POPUP_JAVADOC_INFO = false
            mock.stop()
        } finally {
            super.tearDown()
        }
    }

    private fun calls(): List<String> = ocLog.readLines().filter { it.isNotBlank() }.map { com.google.gson.JsonParser.parseString(it).asString }

    private fun waitFor(what: String, seconds: Int = 60, condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching("Timeout: $what", { condition() }, seconds)

    private fun shot(c: JComponent, name: String, w: Int = 1300, h: Int = 720) {
        // Render the real Swing component off screen.
        c.setSize(w, h)
        layout(c)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        c.paint(g)
        g.dispose()
        ImageIO.write(img, "png", File(shots, name))
    }

    private fun layout(c: Component) {
        c.doLayout()
        if (c is java.awt.Container) c.components.forEach { layout(it) }
    }

    fun testWholeFlow() {
        val service = CrService.of(project)

        // 1. Load: the open notes of the MR of the branch, the pipeline, and the automatic suggestions.
        service.loadBlocking(null)
        assertEquals(listOf("d1", "d2", "d3", "d6"), service.gitlabThreads.map { it.id })
        assertEquals("!7 Feature X (4 open notes)", service.message)
        assertEquals(2, service.gitlabThreads.first { it.id == "d1" }.line)
        assertEquals(listOf("build", "test", "deploy"), crcode.core.groupStages(service.pipeline!!.jobs).map { it.name })
        waitFor("automatic suggestions") {
            service.gitlabThreads.count { it.suggestion != null } == 2 && service.gitlabThreads.count { it.draftReply != null } == 3
        }
        val d1 = service.gitlabThreads.first { it.id == "d1" }
        val d6 = service.gitlabThreads.first { it.id == "d6" }
        assertNull("my last reply: skipped", d6.draftReply)
        assertEquals("fix line 2", d1.suggestion!!.explanation)
        assertEquals("Good point, I changed it.", d1.draftReply)
        println("IT load + automatic suggestions ok, opencode calls: ${calls().size}")

        // 2. The UI shows it: tree, details, inlays in the editor, status bar text, pipeline graph.
        val notes = NotesPanel(project, testRootDisposable)
        val pipelinePanel = PipelinePanel(project, testRootDisposable)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        notes.select(d1)
        shot(notes, "clion-review-notes.png")
        shot(pipelinePanel, "clion-pipeline-graph.png", 1100, 420)
        assertEquals("CI: failed (1)", PipelineWidget(project).also { Disposer.register(testRootDisposable, it) }.text)
        val doc = service.document(root, "src/a.ts")
        val editor = EditorFactory.getInstance().createEditor(doc, project)
        try {
            EditorMarkers.of(project).refresh(editor)
            assertEquals(setOf("d1", "d2", "d6"), EditorMarkers.of(project).threadsIn(editor).map { it.id }.toSet())
            editor.component.setSize(1000, 360)
            shot(editor.component, "clion-editor.png", 1000, 360)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
        println("IT ui ok")

        // 3. Preview and apply the fix; send the draft reply.
        service.previewFix(d1)
        assertEquals("// a\nconst v2 = 'fixed';\nconst v3 = obj.x;", service.lastPreview!!.second["src/a.ts"]!!.lines().take(3).joinToString("\n"))
        service.applyFix(d1)
        assertTrue(doc.text.contains("const v2 = 'fixed';"))
        assertTrue(d1.skipAutoFix)
        service.postReplyBlocking(d1, "Good point, I changed it.")
        assertEquals("Me", d1.notes.last().author)
        FileDocumentManager.getInstance().saveAllDocuments()
        println("IT apply + reply ok")

        // 4. Local change: two lines on top -> notes move down; the changed line is outdated. No new opencode calls for handled notes.
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { doc.insertString(0, "// new 1\n// new 2\n") }
        FileDocumentManager.getInstance().saveDocument(doc)
        val disk = File(root, "src/a.ts").readText()
        assertTrue("disk:\n$disk\ndoc:\n${doc.text}\nsame doc: ${service.document(root, "src/a.ts") === doc}", disk.startsWith("// new 1\n// new 2\n// a\nconst v2 = 'fixed';"))
        val before = calls().size
        service.loadBlocking(null)
        val byId = service.gitlabThreads.associateBy { it.id }
        assertTrue("d1 line was changed", byId["d1"]!!.outdated)
        assertEquals(7, byId["d2"]!!.line)
        assertFalse(byId["d2"]!!.outdated)
        assertEquals(5, byId["d6"]!!.line)
        assertNotNull("cached fix kept", byId["d2"]!!.suggestion)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        Thread.sleep(1500)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("no new opencode calls", before, calls().size)
        println("IT line mapping + cache ok")

        // 5. CI: explain the failed job, apply its fix, retry a job, read a log.
        val job = service.pipeline!!.jobs.first { it.name == "test" }
        val ci = service.explainJobBlocking(job, null)
        assertEquals(ThreadSource.PIPELINE, ci.source)
        assertEquals("src/a.ts", ci.file)
        assertTrue(ci.notes[0].body.contains("The test job fails because line 4 is wrong."))
        assertTrue(calls().last().contains("FAIL src/a.ts line 4 is wrong (job 502)"))
        assertFalse("log cleaned", calls().last().contains("section_start"))
        service.applyFix(ci)
        assertTrue(doc.text.contains("// four fixed"))
        service.jobAction(service.pipeline!!.jobs.first { it.name == "deploy-staging" }, "play")
        waitFor("play request") { mock.requests.any { it == "POST /api/v4/projects/grp%2Fproj/jobs/505/play" } }
        val logText = service.jobLogText(service.session!!, service.pipeline!!.jobs.first { it.name == "lint" })
        assertTrue(logText, logText.startsWith("# test / lint — failed in 12s\n# http://x/j/503\n\n$ npm test\nFAIL src/a.ts line 4 is wrong (job 503)"))
        println("IT pipeline ok")

        // 6. Self review: findings outside the repository are dropped.
        service.selfReviewBlocking(null)
        assertEquals(1, service.selfThreads.size)
        assertEquals("high", service.selfThreads[0].severity)

        // 7. Commit message: the first answer breaks the format, so the plugin asks again; then template, footer and wrap.
        FileDocumentManager.getInstance().saveAllDocuments()
        val n = calls().size
        val msg = service.writeCommitMessageBlocking(null, null)
        assertEquals(2, calls().size - n)
        assertTrue(calls().last().contains("\"type\" is \"feature\", it must be one of feat, fix"))
        assertEquals("feat(api): add login flow\n\nAdds the login flow so users can sign in\nwith the new token format and keeps the\nold one.\n\nRefs: #12 (see !7)", msg)
        println("IT self review + commit message ok")

        // Final screenshots with the CI finding.
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        notes.select(ci)
        shot(notes, "clion-ci-failure.png")
    }
}
