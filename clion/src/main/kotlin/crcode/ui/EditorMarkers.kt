package crcode.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import crcode.core.CrThread
import crcode.core.ThreadSource
import crcode.service.CrListener
import crcode.service.CrService
import crcode.settings.CrSettings
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.io.File
import javax.swing.Icon

/** Shows the notes in the editor: a gutter icon on the line and a box with the note under the line. */
@Service(Service.Level.PROJECT)
class EditorMarkers(private val project: Project) : Disposable {
    private val service = CrService.of(project)
    private val marks = HashMap<Editor, MutableList<Any>>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        service.addListener(object : CrListener {
            override fun changed() = refreshAll()
        }, this)
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) = refresh(event.editor)
            override fun editorReleased(event: EditorFactoryEvent) {
                marks.remove(event.editor)
            }
        }, this)
        EditorFactory.getInstance().eventMulticaster.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(e: EditorMouseEvent) {
                val renderer = e.inlay?.renderer as? NoteRenderer ?: return
                service.openThread(renderer.thread)
            }
        }, this)
        refreshAll()
    }

    fun refreshAll() = EditorFactory.getInstance().allEditors.forEach { refresh(it) }

    /** For tests: the threads marked in an editor. */
    fun threadsIn(editor: Editor): List<CrThread> = marks[editor]?.mapNotNull { (it as? Inlay<*>)?.renderer as? NoteRenderer }?.map { it.thread } ?: emptyList()

    private fun relPath(editor: Editor): String? {
        if (editor.project != null && editor.project != project) return null
        val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val root = service.session?.root ?: return null
        val path = vf.path
        val base = File(root).canonicalPath.replace(File.separatorChar, '/')
        return if (path.startsWith("$base/")) path.substring(base.length + 1) else null
    }

    fun refresh(editor: Editor) {
        if (editor.isDisposed) return
        marks.remove(editor)?.forEach {
            when (it) {
                is RangeHighlighter -> editor.markupModel.removeHighlighter(it)
                is Inlay<*> -> Disposer.dispose(it)
            }
        }
        val rel = relPath(editor) ?: return
        val list = mutableListOf<Any>()
        val byLine = service.threads.filter { it.file == rel && it.line != null }.groupBy { it.line!! }
        val doc = editor.document
        for ((line, threads) in byLine) {
            if (line < 1 || line > doc.lineCount) continue
            val h = editor.markupModel.addLineHighlighter(line - 1, HighlighterLayer.ADDITIONAL_SYNTAX, null as TextAttributes?)
            h.gutterIconRenderer = Gutter(threads)
            list.add(h)
            if (CrSettings.app().state.showInlays) {
                for (t in threads) {
                    editor.inlayModel.addBlockElement(doc.getLineEndOffset(line - 1), true, false, 100, NoteRenderer(t, editor))?.let { list.add(it) }
                }
            }
        }
        marks[editor] = list
    }

    override fun dispose() {
        marks.forEach { (editor, list) ->
            if (!editor.isDisposed) list.forEach { if (it is RangeHighlighter) editor.markupModel.removeHighlighter(it) else if (it is Inlay<*>) Disposer.dispose(it) }
        }
        marks.clear()
    }

    private inner class Gutter(val threads: List<CrThread>) : GutterIconRenderer() {
        override fun getIcon(): Icon = CrIcons.thread(threads.first())
        override fun getTooltipText(): String =
            "<html>" + threads.joinToString("<hr>") { t ->
                "<b>${Html.esc(t.label)}</b><br>" + t.notes.joinToString("<br>") { "<b>${Html.esc(it.author)}:</b> ${Html.esc(it.body.take(300))}" } +
                    (if (t.suggestion != null) "<br><i>opencode has a fix. Click to open it.</i>" else "")
            } + "</html>"
        override fun getClickAction(): AnAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = service.openThread(threads.first())
        }
        override fun isNavigateAction() = true
        override fun equals(other: Any?) = other is Gutter && other.threads.map { it.id } == threads.map { it.id }
        override fun hashCode() = threads.map { it.id }.hashCode()
    }

    /** The box under the line: who wrote the note, its first line, and what opencode prepared. */
    class NoteRenderer(val thread: CrThread, private val editor: Editor) : EditorCustomElementRenderer {
        private fun text(): String {
            val last = thread.notes.lastOrNull()
            val tags = listOfNotNull(
                if (thread.outdated) "outdated" else null,
                if (thread.notes.size > 1) "${thread.notes.size} notes" else null,
                if (thread.suggestion != null) "fix ready" else null,
                if (thread.draftReply != null) "reply draft" else null,
                if (thread.resolved) "resolved" else null,
            )
            val who = when (thread.source) {
                ThreadSource.GITLAB -> last?.author ?: "?"
                ThreadSource.SELF -> "Self review"
                ThreadSource.PIPELINE -> "CI failure · ${thread.subtitle ?: ""}"
            }
            val body = crcode.core.plainTitle(if (thread.source == ThreadSource.GITLAB) last?.body ?: "" else thread.notes.first().body.lines().drop(if (thread.source == ThreadSource.PIPELINE) 2 else 0).joinToString("\n"))
            return "$who: $body" + if (tags.isNotEmpty()) "   [${tags.joinToString(" · ")}]" else ""
        }

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val fm = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN))
            return fm.stringWidth(text()) + JBUI.scale(36) + icon().iconWidth + indentX()
        }

        override fun calcHeightInPixels(inlay: Inlay<*>): Int = editor.lineHeight + JBUI.scale(6)

        private fun icon(): Icon = CrIcons.thread(thread)

        private fun indentX(): Int {
            val line = thread.line ?: return 0
            val doc = editor.document
            if (line < 1 || line > doc.lineCount) return 0
            val start = doc.getLineStartOffset(line - 1)
            val text = doc.charsSequence.subSequence(start, doc.getLineEndOffset(line - 1))
            val indent = text.takeWhile { it == ' ' || it == '\t' }.length
            return editor.offsetToXY(start + indent).x - editor.offsetToXY(start).x
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, r: Rectangle, attrs: TextAttributes) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val x = r.x + indentX()
            val w = r.width - indentX()
            val color = when (thread.source) {
                ThreadSource.GITLAB -> JBColor(0x1F75CB, 0x428FDC)
                ThreadSource.SELF -> JBColor(0x6E49CB, 0x9475DB)
                ThreadSource.PIPELINE -> JBColor(0xDD2B0E, 0xEC5941)
            }
            g2.color = JBColor(java.awt.Color(color.red, color.green, color.blue, 28), java.awt.Color(color.red, color.green, color.blue, 40))
            g2.fillRoundRect(x, r.y + JBUI.scale(2), w, r.height - JBUI.scale(4), JBUI.scale(8), JBUI.scale(8))
            g2.color = color
            g2.fillRect(x, r.y + JBUI.scale(2), JBUI.scale(3), r.height - JBUI.scale(4))
            val icon = icon()
            icon.paintIcon(editor.contentComponent, g2, x + JBUI.scale(9), r.y + (r.height - icon.iconHeight) / 2)
            g2.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
            g2.color = if (thread.resolved) JBColor.GRAY else editor.colorsScheme.defaultForeground
            g2.drawString(text(), x + JBUI.scale(15) + icon.iconWidth, r.y + (r.height + g2.fontMetrics.ascent - g2.fontMetrics.descent) / 2)
            g2.dispose()
        }
    }

    companion object {
        fun of(project: Project): EditorMarkers = project.getService(EditorMarkers::class.java)
    }
}
