package crcode.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import crcode.service.CrService
import crcode.ui.CrToolWindowFactory

/** Base for the tool window and Tools menu actions: needs a project; errors become notifications. */
abstract class CrAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = CrService.of(project)
        try {
            run(service, e)
        } catch (ex: Exception) {
            service.error(ex, templatePresentation.text ?: "GitLab CR")
        }
    }

    abstract fun run(service: CrService, e: AnActionEvent)
}

class RefreshAction : CrAction() {
    override fun run(service: CrService, e: AnActionEvent) = service.refresh()
}

class FixAllAction : CrAction() {
    override fun run(service: CrService, e: AnActionEvent) {
        var pool = service.gitlabThreads
        if (service.selfThreads.isNotEmpty() || service.pipelineThreads.isNotEmpty()) {
            val options = mutableListOf("GitLab notes" to service.gitlabThreads)
            if (service.selfThreads.isNotEmpty()) options.add("Self review" to service.selfThreads)
            if (service.pipelineThreads.isNotEmpty()) options.add("CI failures" to service.pipelineThreads)
            options.add("All" to service.threads)
            val i = Messages.showDialog(service.project, "Which notes do you want to fix?", "Fix All CR Notes", (options.map { it.first } + "Cancel").toTypedArray(), 0, Messages.getQuestionIcon())
            if (i < 0 || i >= options.size) return
            pool = options[i].second
        }
        service.fixAll(pool)
    }
}

class SelfReviewAction : CrAction() {
    override fun run(service: CrService, e: AnActionEvent) = service.selfReview()
}

class ClearSelfReviewAction : CrAction() {
    override fun run(service: CrService, e: AnActionEvent) = service.clearSelfReview()
}

class ShowPipelineAction : CrAction() {
    override fun run(service: CrService, e: AnActionEvent) {
        if (service.pipeline == null) service.refreshPipeline()
        CrToolWindowFactory.show(service.project, "Pipeline")
    }
}

class RefreshPipelineAction : CrAction() {
    override fun run(service: CrService, e: AnActionEvent) = service.refreshPipeline()
}

class FixPipelineAction : CrAction() {
    override fun run(service: CrService, e: AnActionEvent) {
        val failed = service.pipeline?.jobs?.filter { it.status == "failed" } ?: emptyList()
        if (failed.isEmpty()) {
            service.notify("The latest pipeline has no failed job.")
            return
        }
        service.startAutoExplain(failed, true)
    }
}

class OpenMergeRequestAction : CrAction() {
    override fun run(service: CrService, e: AnActionEvent) = service.openInBrowser()
}

class SetTokenAction : CrAction() {
    override fun run(service: CrService, e: AnActionEvent) = service.askToken()
}

class ShowLogAction : CrAction() {
    override fun run(service: CrService, e: AnActionEvent) = service.showLog()
}

class OpenSettingsAction : CrAction() {
    override fun run(service: CrService, e: AnActionEvent) = ShowSettingsUtil.getInstance().showSettingsDialog(service.project, "GitLab CR + opencode")
}
