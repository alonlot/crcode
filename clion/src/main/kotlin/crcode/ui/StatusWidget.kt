package crcode.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import crcode.core.statusLabel
import crcode.service.CrListener
import crcode.service.CrService
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

class PipelineWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = PipelineWidget.ID
    override fun getDisplayName() = "GitLab CR: Pipeline"
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project): StatusBarWidget = PipelineWidget(project)
}

/** "CI: failed (1)" in the status bar. Click opens the pipeline graph. */
class PipelineWidget(private val project: Project) : CustomStatusBarWidget {
    private val label = JBLabel()

    init {
        label.border = JBUI.Borders.empty(0, 6)
        label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = CrToolWindowFactory.show(project, "Pipeline")
        })
        CrService.of(project).addListener(object : CrListener {
            override fun changed() = update()
        }, this)
        update()
    }

    /** For tests. */
    val text: String get() = label.text

    fun update() {
        val info = CrService.of(project).pipeline
        if (info == null) {
            label.isVisible = false
            return
        }
        val failed = CrService.failedJobs(info).size
        label.isVisible = true
        label.icon = CrIcons.status(info.pipeline.status)
        label.text = "CI: ${statusLabel(info.pipeline.status)}" + if (failed > 0) " ($failed)" else ""
        label.isOpaque = info.pipeline.status == "failed"
        label.background = CrIcons.color("failed")
        label.foreground = if (info.pipeline.status == "failed") Color.WHITE else null
        label.toolTipText = "<html><b>Pipeline #${info.pipeline.id}</b>: ${statusLabel(info.pipeline.status)}<br>" +
            info.jobs.joinToString("<br>") { "${Html.esc(it.stage)} / ${Html.esc(it.name)}: ${statusLabel(crcode.core.shownStatus(it))}" } +
            "<br><i>Click for the pipeline graph.</i></html>"
        label.repaint()
    }

    override fun ID() = ID
    override fun getComponent(): JComponent = label
    override fun install(statusBar: StatusBar) {}
    override fun dispose() {}

    companion object {
        const val ID = "gitlabCr.pipeline"
    }
}
