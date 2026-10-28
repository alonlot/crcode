package crcode.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.ChangesUtil
import crcode.service.CrService
import java.io.File

/** "Write Commit Message (opencode)" in the commit message toolbar. */
class CommitMessageAction : AnAction("Write Commit Message (opencode)", "Write the commit message in the configured format with opencode", AllIcons.Actions.Lightning), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val control = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val service = CrService.of(project)
        val files = includedFiles(e, service)
        service.background("opencode: writing the commit message") { indicator ->
            val message = service.writeCommitMessageBlocking(files, indicator)
            ApplicationManager.getApplication().invokeLater { control.setCommitMessage(message) }
        }
    }

    /** The files checked in the commit tool window, relative to the repository root, or null when they are not known. */
    private fun includedFiles(e: AnActionEvent, service: CrService): List<String>? = try {
        val ui = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) ?: return null
        val root = File(service.repoRoot()).canonicalFile
        val paths: List<File> = ui.getIncludedChanges().map { ChangesUtil.getFilePath(it).ioFile } + ui.getIncludedUnversionedFiles().map { it.ioFile }
        paths.map { it.canonicalFile }
            .filter { it.path.startsWith(root.path + File.separator) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .distinct()
            .takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }
}
