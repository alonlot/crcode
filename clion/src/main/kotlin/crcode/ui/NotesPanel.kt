package crcode.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.OnePixelDivider
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import crcode.core.CrThread
import crcode.core.ThreadSource
import crcode.core.formatDuration
import crcode.core.groupStages
import crcode.core.jobActions
import crcode.core.renderDiff
import crcode.core.shownStatus
import crcode.core.statusLabel
import crcode.gitlab.Job
import crcode.service.CrListener
import crcode.service.CrService
import crcode.service.PipelineInfo
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class GroupNode(val label: String, val icon: javax.swing.Icon)
class PipelineNode(val info: PipelineInfo)
class StageNode(val name: String, val status: String, val jobs: List<Job>)
class JobNode(val job: Job)

/** "Review Notes" tab: the tree on the left, the selected note with its buttons on the right. */
class NotesPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val service = CrService.of(project)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    val tree = Tree(model)
    private val header = JBLabel()
    private val details = JPanel(BorderLayout())
    /** Reply text typed by the user, per thread, so a refresh does not lose it. */
    private val typed = HashMap<String, String>()
    private var shown: Any? = null

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        tree.addTreeSelectionListener { showDetails(selected()) }
        TreeUtil.installActions(tree)

        val toolbar = ActionManager.getInstance().createActionToolbar("GitLabCrToolbar", ActionManager.getInstance().getAction("GitLabCr.Toolbar") as ActionGroup, true)
        toolbar.targetComponent = this
        val top = JPanel(BorderLayout())
        top.add(toolbar.component, BorderLayout.WEST)
        header.border = JBUI.Borders.empty(0, 8)
        top.add(header, BorderLayout.CENTER)
        top.border = BorderFactory.createMatteBorder(0, 0, 1, 0, OnePixelDivider.BACKGROUND)
        add(top, BorderLayout.NORTH)

        val splitter = JBSplitter(false, "gitlabCr.notes.splitter", 0.4f)
        splitter.firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
        splitter.secondComponent = details
        add(splitter, BorderLayout.CENTER)

        service.addListener(object : CrListener {
            override fun changed() = rebuild()
            override fun showThread(t: CrThread) = select(t)
        }, parent)
        rebuild()
    }

    fun selected(): Any? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject

    private fun key(o: Any?): String? = when (o) {
        is CrThread -> "t:" + o.id
        is JobNode -> "j:" + o.job.id
        is StageNode -> "s:" + o.name
        is PipelineNode -> "p"
        is GroupNode -> "g:" + o.label.substringBefore(" (")
        else -> null
    }

    fun rebuild() {
        val selectedKey = key(selected())
        val collapsed = mutableSetOf<String>()
        TreeUtil.treeNodeTraverser(root).forEach { n ->
            val node = n as DefaultMutableTreeNode
            if (node !== root && !node.isLeaf && !tree.isExpanded(TreePath(node.path))) key(node.userObject)?.let { collapsed.add(it) }
        }
        root.removeAllChildren()
        service.pipeline?.let { info ->
            val pn = DefaultMutableTreeNode(PipelineNode(info))
            for (st in groupStages(info.jobs)) {
                val sn = DefaultMutableTreeNode(StageNode(st.name, st.status, st.jobs))
                st.jobs.forEach { sn.add(DefaultMutableTreeNode(JobNode(it))) }
                pn.add(sn)
            }
            root.add(pn)
        }
        fun group(label: String, icon: javax.swing.Icon, threads: List<CrThread>) {
            if (threads.isEmpty()) return
            val g = DefaultMutableTreeNode(GroupNode("$label (${threads.size})", icon))
            threads.sortedWith(compareBy({ it.file ?: "" }, { it.line ?: 0 })).forEach { g.add(DefaultMutableTreeNode(it)) }
            root.add(g)
        }
        group("CI failures", AllIcons.RunConfigurations.TestFailed, service.pipelineThreads)
        group("Review notes", AllIcons.General.Balloon, service.gitlabThreads)
        group("Self review", AllIcons.General.InspectionsEye, service.selfThreads)
        model.reload()
        TreeUtil.treeNodeTraverser(root).forEach { n ->
            val node = n as DefaultMutableTreeNode
            if (node !== root && !node.isLeaf && key(node.userObject) !in collapsed) tree.expandPath(TreePath(node.path))
        }
        header.text = if (service.loading) "Loading…" else service.message
        val again = selectedKey?.let { k -> TreeUtil.treeNodeTraverser(root).find { key((it as DefaultMutableTreeNode).userObject) == k } as? DefaultMutableTreeNode }
        if (again != null) {
            tree.selectionPath = TreePath(again.path)
            showDetails(again.userObject, force = true)
        } else {
            showDetails(selected(), force = true)
        }
    }

    fun select(t: CrThread) {
        val node = TreeUtil.treeNodeTraverser(root).find { ((it as DefaultMutableTreeNode).userObject as? CrThread)?.id == t.id } as? DefaultMutableTreeNode ?: return
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun showDetails(o: Any?, force: Boolean = false) {
        if (!force && o === shown) return
        shown = o
        details.removeAll()
        val content: JComponent = when (o) {
            is CrThread -> threadDetails(o)
            is JobNode -> jobDetails(o.job)
            is PipelineNode -> pipelineDetails(o.info)
            is StageNode -> text("<b>Stage ${Html.esc(o.name)}</b>: ${Html.esc(statusLabel(o.status))}<br>${o.jobs.size} job(s)")
            else -> text(
                if (service.session == null) "No review notes loaded.<br><br>Click <b>Refresh</b> in the toolbar. Set the token with the key icon."
                else Html.esc(service.message),
            )
        }
        details.add(JBScrollPane(content).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        details.revalidate()
        details.repaint()
    }

    private fun text(html: String): JComponent {
        val pane = JEditorPane()
        pane.editorKit = UIUtil.getHTMLEditorKit()
        pane.isEditable = false
        pane.isOpaque = false
        pane.text = "<html><body>$html</body></html>"
        pane.addHyperlinkListener { if (it.eventType == HyperlinkEvent.EventType.ACTIVATED) BrowserUtil.browse(it.url ?: return@addHyperlinkListener) }
        return pane
    }

    private fun section(title: String?, vararg parts: JComponent): JPanel {
        val p = JPanel()
        p.layout = BoxLayout(p, BoxLayout.Y_AXIS)
        p.border = JBUI.Borders.empty(8, 10, 4, 10)
        p.alignmentX = 0f
        if (title != null) p.add(JBLabel(title).apply { font = font.deriveFont(Font.BOLD); alignmentX = 0f; border = JBUI.Borders.emptyBottom(4) })
        parts.forEach { it.alignmentX = 0f; p.add(it) }
        return p
    }

    private fun buttons(vararg b: Pair<String, () -> Unit>?): JPanel {
        val p = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        p.alignmentX = 0f
        for (pair in b.filterNotNull()) {
            val button = JButton(pair.first)
            button.addActionListener {
                try {
                    pair.second()
                } catch (e: Exception) {
                    service.error(e, pair.first)
                }
            }
            p.add(button)
        }
        p.maximumSize = java.awt.Dimension(Int.MAX_VALUE, p.preferredSize.height)
        return p
    }

    private fun threadDetails(t: CrThread): JComponent {
        val box = JPanel()
        box.layout = BoxLayout(box, BoxLayout.Y_AXIS)
        val conversation = t.notes.joinToString("<hr>") { n ->
            "<b>${Html.esc(n.author)}</b> <span style='color:gray'>${Html.esc(n.createdAt?.take(16)?.replace('T', ' ') ?: "")}</span><br>${Html.markdown(n.body)}"
        }
        box.add(section(null, text("<span style='color:gray'>${Html.esc(t.label)} · ${Html.esc(t.location)}</span><br><br>$conversation")))
        box.add(
            buttons(
                if (t.file != null) "Open in Editor" to { service.openThread(t) } else null,
                if (t.file != null) "Suggest Fix" to { service.suggestFix(t) } else null,
                if (t.source == ThreadSource.GITLAB && t.resolvable) (if (t.resolved) "Unresolve" else "Resolve") to { service.toggleResolved(t) } else null,
                if (t.source == ThreadSource.SELF) "Post to Merge Request" to { service.postFinding(t) } else null,
                if (t.source != ThreadSource.GITLAB) "Dismiss" to { service.dismissFinding(t) } else null,
                t.jobUrl?.let { url -> "Open Job in GitLab" to { BrowserUtil.browse(url) } },
            ),
        )
        t.suggestion?.let { s ->
            val diff = JBTextArea(s.edits.joinToString("\n\n") { "${it.file}\n" + renderDiff(it.oldText.removeSuffix("\n"), it.newText, it.startLine) }.ifEmpty { "opencode suggests no code change." })
            diff.isEditable = false
            diff.font = UIUtil.getFontWithFallback("JetBrains Mono", Font.PLAIN, UIUtil.getLabelFont().size)
            diff.border = JBUI.Borders.empty(6)
            box.add(section("Suggested fix (opencode)", text(Html.esc(s.explanation.ifEmpty { "(no explanation)" })), diff))
            box.add(
                buttons(
                    if (s.edits.isNotEmpty()) "Preview" to { service.previewFix(t) } else null,
                    if (s.edits.isNotEmpty()) "Apply Fix" to { service.applyFix(t) } else null,
                    "Discard" to { service.discardFix(t) },
                ),
            )
        }
        if (t.source == ThreadSource.GITLAB) {
            val area = JBTextArea(typed[t.id] ?: t.draftReply ?: "", 5, 40)
            area.lineWrap = true
            area.wrapStyleWord = true
            area.border = JBUI.Borders.empty(4)
            area.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = save()
                override fun removeUpdate(e: DocumentEvent) = save()
                override fun changedUpdate(e: DocumentEvent) = save()
                fun save() {
                    typed[t.id] = area.text
                }
            })
            val title = if (t.draftReply != null) "Reply (draft by opencode, edit it before you send)" else "Reply"
            box.add(section(title, JBScrollPane(area).apply { preferredSize = JBUI.size(400, 110) }))
            box.add(
                buttons(
                    "Send Reply" to {
                        val text = area.text.trim()
                        if (text.isNotEmpty()) {
                            typed.remove(t.id)
                            service.sendDraft(t, text)
                        }
                    },
                    "Suggest Reply" to { typed.remove(t.id); service.suggestReply(t) },
                    if (t.draftReply != null) "Discard Draft" to { typed.remove(t.id); service.discardDraft(t) } else null,
                ),
            )
        }
        box.add(Box.createVerticalGlue())
        return box
    }

    private fun jobDetails(j: Job): JComponent {
        val box = JPanel()
        box.layout = BoxLayout(box, BoxLayout.Y_AXIS)
        val st = shownStatus(j)
        box.add(
            section(
                null,
                text(
                    "<b>${Html.esc(j.name)}</b> (stage ${Html.esc(j.stage)})<br>Status: ${Html.esc(statusLabel(st))}" +
                        (j.duration?.let { "<br>Duration: ${formatDuration(it)}" } ?: "") +
                        (j.queuedDuration?.let { "<br>Queued: ${formatDuration(it)}" } ?: "") +
                        (j.failureReason?.let { "<br>Failure reason: ${Html.esc(it.replace('_', ' '))}" } ?: ""),
                ),
            ),
        )
        box.add(jobButtons(j))
        box.add(Box.createVerticalGlue())
        return box
    }

    fun jobButtons(j: Job): JPanel = buttons(*JobMenu.actions(service, j).map { it.first to it.second }.toTypedArray())

    private fun pipelineDetails(info: PipelineInfo): JComponent {
        val p = info.pipeline
        val failed = CrService.failedJobs(info)
        val box = JPanel()
        box.layout = BoxLayout(box, BoxLayout.Y_AXIS)
        box.add(
            section(
                null,
                text(
                    "<b>Pipeline #${p.id}</b>: ${Html.esc(statusLabel(p.status))}<br>${Html.esc(p.ref)} <code>${Html.esc(p.sha.take(8))}</code>" +
                        (p.duration?.let { " · ${formatDuration(it)}" } ?: "") + (p.userName?.let { " · by ${Html.esc(it)}" } ?: "") +
                        "<br>${info.jobs.size} job(s)" + if (failed.isNotEmpty()) ", ${failed.size} failed" else "",
                ),
            ),
        )
        box.add(
            buttons(
                "Show Graph" to { service.requestShowPipeline() },
                "Refresh" to { service.refreshPipeline() },
                if (failed.isNotEmpty()) "Explain Failed Jobs" to { service.startAutoExplain(failed, true) } else null,
                "Open in GitLab" to { BrowserUtil.browse(p.webUrl) },
            ),
        )
        box.add(Box.createVerticalGlue())
        return box
    }

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val o = (value as? DefaultMutableTreeNode)?.userObject) {
                is GroupNode -> {
                    icon = o.icon
                    append(o.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
                is PipelineNode -> {
                    icon = CrIcons.status(o.info.pipeline.status)
                    append("Pipeline #${o.info.pipeline.id}: ${statusLabel(o.info.pipeline.status)}")
                    val failed = CrService.failedJobs(o.info).size
                    append("  " + if (failed > 0) "$failed job(s) failed" else "${o.info.jobs.size} job(s)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is StageNode -> {
                    icon = CrIcons.status(o.status)
                    append(o.name)
                    append("  ${statusLabel(o.status)} · ${o.jobs.size} job(s)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is JobNode -> {
                    val st = shownStatus(o.job)
                    icon = CrIcons.status(st)
                    append(o.job.name)
                    append("  ${statusLabel(st)}" + (o.job.duration?.let { " · ${formatDuration(it)}" } ?: ""), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (service.pipelineThreads.any { it.id == "pipeline-${o.job.id}" }) append("  💡", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is CrThread -> {
                    icon = CrIcons.thread(o)
                    append(o.title, if (o.resolved) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ${o.location}${if (o.outdated) " (outdated)" else ""}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (o.draftReply != null) append("  ✎", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                else -> {}
            }
        }
    }
}

/** The actions of one CI job, for the details buttons, the graph popup and the tree. */
object JobMenu {
    fun actions(service: CrService, j: Job): List<Triple<String, () -> Unit, javax.swing.Icon>> {
        val list = mutableListOf<Triple<String, () -> Unit, javax.swing.Icon>>()
        list.add(Triple("Show Log", { service.showJobLog(j) }, AllIcons.Debugger.Console))
        if (j.status == "failed") list.add(Triple("Explain and Fix (opencode)", { service.fixJob(j) }, AllIcons.Actions.IntentionBulb))
        service.pipelineThreads.find { it.id == "pipeline-${j.id}" }?.let { t -> list.add(Triple("Show opencode Finding", { service.openThread(t) }, AllIcons.General.Balloon)) }
        for (a in jobActions(j.status)) {
            when (a) {
                crcode.core.JobAction.RETRY -> list.add(Triple("Retry", { service.jobAction(j, "retry") }, AllIcons.Actions.Restart))
                crcode.core.JobAction.PLAY -> list.add(Triple("Run", { service.jobAction(j, "play") }, AllIcons.Actions.Execute))
                crcode.core.JobAction.CANCEL -> list.add(Triple("Cancel", { service.jobAction(j, "cancel") }, AllIcons.Actions.Suspend))
            }
        }
        list.add(Triple("Open in GitLab", { BrowserUtil.browse(j.webUrl) }, AllIcons.Ide.External_link_arrow))
        return list
    }
}
