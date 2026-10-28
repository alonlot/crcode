package crcode.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import crcode.core.CrThread
import crcode.service.CrListener
import crcode.service.CrService

class CrToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        val notes = NotesPanel(project, toolWindow.disposable)
        val notesContent = factory.createContent(notes, "Review Notes", false)
        val pipeline = PipelinePanel(project, toolWindow.disposable)
        val pipelineContent = factory.createContent(pipeline, "Pipeline", false)
        toolWindow.contentManager.addContent(notesContent)
        toolWindow.contentManager.addContent(pipelineContent)
        CrService.of(project).addListener(object : CrListener {
            override fun showThread(t: CrThread) {
                toolWindow.show()
                toolWindow.contentManager.setSelectedContent(notesContent)
                notes.select(t)
            }

            override fun showPipeline() {
                toolWindow.show()
                toolWindow.contentManager.setSelectedContent(pipelineContent)
            }
        }, toolWindow.disposable)
    }

    companion object {
        const val ID = "GitLab CR"

        fun show(project: Project, tab: String? = null) {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            tw.show {
                if (tab != null) tw.contentManager.findContent(tab)?.let { tw.contentManager.setSelectedContent(it) }
            }
        }
    }
}
