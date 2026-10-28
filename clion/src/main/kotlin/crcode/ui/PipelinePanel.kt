package crcode.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import crcode.core.CiStage
import crcode.core.formatDuration
import crcode.core.groupStages
import crcode.core.shownStatus
import crcode.core.statusLabel
import crcode.gitlab.Job
import crcode.service.CrListener
import crcode.service.CrService
import crcode.service.PipelineInfo
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** "Pipeline" tab: the pipeline graph as on the GitLab merge request page. */
class PipelinePanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val service = CrService.of(project)
    private val header = JPanel()
    private val graph = GraphPanel()
    private val footer = JBLabel()

    init {
        header.layout = BoxLayout(header, BoxLayout.Y_AXIS)
        header.border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12))
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(graph).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        footer.foreground = UIUtil.getContextHelpForeground()
        footer.border = JBUI.Borders.empty(4, 12)
        add(footer, BorderLayout.SOUTH)
        service.addListener(object : CrListener {
            override fun changed() = rebuild()
        }, parent)
        rebuild()
    }

    fun rebuild() {
        header.removeAll()
        val info = service.pipeline
        if (info == null) {
            header.add(JBLabel(if (service.session == null) "No merge request loaded." else "No pipeline for the merge request of the current branch."))
            header.add(row(button("Refresh") { service.refreshPipeline() }))
            graph.setStages(emptyList(), null)
        } else {
            val p = info.pipeline
            val failed = CrService.failedJobs(info)
            val meta = listOfNotNull(
                "Pipeline #${p.id}" + (service.session?.mr?.let { " for !${it.iid}" } ?: ""),
                "${p.ref}  ${p.sha.take(8)}",
                p.duration?.let { "⏱ ${formatDuration(it)}" },
                p.userName?.let { "by $it" },
                p.source?.replace('_', ' '),
            )
            header.add(row(Badge(p.status), *meta.map { JBLabel(it) }.toTypedArray()))
            val counts = listOfNotNull(
                "${info.jobs.size} jobs",
                info.jobs.count { it.status == "success" }.takeIf { it > 0 }?.let { "$it passed" },
                failed.size.takeIf { it > 0 }?.let { "$it failed" },
                info.jobs.count { it.status == "running" }.takeIf { it > 0 }?.let { "$it running" },
            )
            header.add(row(JBLabel(counts.joinToString("  ·  ")).apply { foreground = UIUtil.getContextHelpForeground() }))
            header.add(
                row(
                    button("Refresh") { service.refreshPipeline() },
                    if (p.status == "failed" || p.status == "canceled") button("Retry Failed Jobs") { service.pipelineAction("retry") } else null,
                    if (p.status == "running" || p.status == "pending") button("Cancel Pipeline") { service.pipelineAction("cancel") } else null,
                    if (failed.isNotEmpty()) button("Explain Failed Jobs") { service.startAutoExplain(info.jobs.filter { it.status == "failed" }, true) } else null,
                    button("Open in GitLab") { BrowserUtil.browse(p.webUrl) },
                ),
            )
            graph.setStages(groupStages(info.jobs), info)
        }
        footer.text = "Updated ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}. Click a job for its actions; double-click opens the log."
        header.revalidate()
        header.repaint()
    }

    private fun row(vararg c: JComponent?): JPanel {
        val p = JPanel(FlowLayout(FlowLayout.LEFT, 8, 3))
        p.alignmentX = 0f
        c.filterNotNull().forEach { p.add(it) }
        return p
    }

    private fun button(text: String, fn: () -> Unit) = JButton(text).apply {
        addActionListener {
            try {
                fn()
            } catch (e: Exception) {
                service.error(e, text)
            }
        }
    }

    /** Rounded status badge, filled with the GitLab status color. */
    private class Badge(val status: String) : JBLabel(statusLabel(status), CrIcons.status(status), SwingConstants.LEFT) {
        init {
            foreground = Color.WHITE
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(3, 6, 3, 10)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = CrIcons.color(status)
            g2.fillRoundRect(0, 0, width, height, height, height)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    /** One column per stage, one pill per job, with lines between the stages. */
    inner class GraphPanel : JPanel(null) {
        private val columns = mutableListOf<JPanel>()
        private val gap = JBUI.scale(44)
        private val columnWidth = JBUI.scale(210)

        init {
            isOpaque = true
            background = UIUtil.getPanelBackground()
        }

        /** For tests: the shown stages. */
        var stages: List<CiStage<Job>> = emptyList()
            private set

        fun setStages(stages: List<CiStage<Job>>, info: PipelineInfo?) {
            this.stages = stages
            removeAll()
            columns.clear()
            for (st in stages) {
                val col = JPanel()
                col.layout = BoxLayout(col, BoxLayout.Y_AXIS)
                col.isOpaque = false
                val head = JBLabel("${st.name}  ", CrIcons.status(st.status), SwingConstants.LEFT)
                head.font = head.font.deriveFont(Font.BOLD)
                val count = JBLabel("${st.jobs.size}").apply { foreground = UIUtil.getContextHelpForeground() }
                val headRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(head); add(count); alignmentX = 0f }
                headRow.maximumSize = Dimension(columnWidth, headRow.preferredSize.height)
                col.add(headRow)
                col.add(Box.createVerticalStrut(JBUI.scale(8)))
                for (j in st.jobs) {
                    col.add(Pill(j))
                    col.add(Box.createVerticalStrut(JBUI.scale(8)))
                }
                columns.add(col)
                add(col)
            }
            revalidate()
            repaint()
        }

        override fun doLayout() {
            var x = JBUI.scale(12)
            for (c in columns) {
                val h = c.preferredSize.height
                c.setBounds(x, JBUI.scale(12), columnWidth, h)
                x += columnWidth + gap
            }
        }

        override fun getPreferredSize(): Dimension {
            val w = JBUI.scale(24) + columns.size * columnWidth + maxOf(0, columns.size - 1) * gap
            val h = JBUI.scale(24) + (columns.maxOfOrNull { it.preferredSize.height } ?: 0)
            return Dimension(w, h)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (columns.size < 2) return
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBColor.border()
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
            // Line from the first job of a stage to the first job of the next stage, as GitLab draws it.
            for (i in 0 until columns.size - 1) {
                val a = columns[i]
                val b = columns[i + 1]
                val firstA = a.components.firstOrNull { it is Pill } ?: continue
                val firstB = b.components.firstOrNull { it is Pill } ?: continue
                val y1 = a.y + firstA.y + firstA.height / 2
                val y2 = b.y + firstB.y + firstB.height / 2
                g2.drawLine(a.x + a.width, y1, b.x, y2)
            }
            g2.dispose()
        }
    }

    /** One job: status icon, name and duration in a rounded box. Click shows the actions. */
    inner class Pill(val job: Job) : JPanel(BorderLayout()) {
        private var hover = false
        private val status = shownStatus(job)

        init {
            isOpaque = false
            alignmentX = 0f
            border = JBUI.Borders.empty(5, 8)
            val hasFinding = service.pipelineThreads.any { it.id == "pipeline-${job.id}" }
            add(JBLabel(job.name + if (hasFinding) "  💡" else "", CrIcons.status(status), SwingConstants.LEFT), BorderLayout.CENTER)
            add(JBLabel(formatDuration(job.duration)).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.EAST)
            toolTipText = "<html><b>${Html.esc(job.name)}</b>: ${Html.esc(statusLabel(status))}" +
                (job.queuedDuration?.let { "<br>queued ${formatDuration(it)}" } ?: "") +
                (job.failureReason?.let { "<br>${Html.esc(it.replace('_', ' '))}" } ?: "") + "</html>"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            maximumSize = Dimension(JBUI.scale(210), preferredSize.height)
            preferredSize = Dimension(JBUI.scale(210), preferredSize.height)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) run { service.showJobLog(job) } else showMenu(e)
                }
            })
        }

        private fun run(fn: () -> Unit) = try {
            fn()
        } catch (ex: Exception) {
            service.error(ex, job.name)
        }

        private fun showMenu(e: MouseEvent) {
            val menu = JBPopupMenu()
            for ((title, fn, icon) in JobMenu.actions(service, job)) {
                menu.add(JBMenuItem(title, icon).apply { addActionListener { run(fn) } })
            }
            menu.show(this, e.x, e.y)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(10)
            g2.color = UIUtil.getListBackground()
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = if (hover) JBUI.CurrentTheme.Focus.focusColor() else if (status == "failed") CrIcons.color("failed") else JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
    }
}
