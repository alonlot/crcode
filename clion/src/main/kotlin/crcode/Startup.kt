package crcode

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import crcode.service.CrService
import crcode.ui.EditorMarkers

/** Loads the merge request of the current branch when the project opens (when a token is saved). */
class CrStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            EditorMarkers.of(project).start()
            ApplicationManager.getApplication().executeOnPooledThread { CrService.of(project).start() }
        }
    }
}
