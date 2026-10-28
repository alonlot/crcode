package crcode.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import crcode.service.CrService

/** Settings | Tools | GitLab CR + opencode. */
class CrConfigurable(private val project: Project) : BoundSearchableConfigurable("GitLab CR + opencode", "gitlabCr", "crcode.settings") {
    override fun createPanel() = panel {
        val app = CrSettings.app().state
        val ps = CrProjectSettings.of(project).state
        group("GitLab (this project, kept in .idea/gitlabCrProject.xml)") {
            row("GitLab URL:") { textField().bindText(ps::baseUrl).align(AlignX.FILL).comment("Empty: read from the git remote. Add the path for a relative URL root, for example https://example.com/gitlab.") }
            row("Project path:") { textField().bindText(ps::projectPath).align(AlignX.FILL).comment("group/subgroup/project or the numeric ID. Empty: read from the git remote.") }
            row("Git remote:") { textField().bindText(ps::remoteName) }
            row { checkBox("Verify the TLS certificate of the GitLab server").bindSelected(app::strictSSL).comment("Turn off only for a self-signed certificate you trust. Certificates you accepted in the IDE are trusted.") }
            row { checkBox("Show resolved discussions").bindSelected(ps::showResolved) }
            row { button("Set Access Token…") { CrService.of(project).askToken() }.comment("Kept in the IDE password safe. The GITLAB_TOKEN environment variable also works.") }
        }
        group("opencode") {
            row("Executable:") { textField().bindText(app::opencodePath).align(AlignX.FILL) }
            row("Model:") { textField().bindText(app::opencodeModel).comment("provider/model. Empty: the opencode default.") }
            row("Agent:") { textField().bindText(app::opencodeAgent).comment("The plan agent can read the repository but cannot edit it. The plugin applies the edits itself, after you click Apply.") }
            row("Timeout (seconds):") { intTextField(10..3600).bindIntText(app::opencodeTimeoutSeconds) }
            row("Parallel requests:") { intTextField(1..8).bindIntText(app::concurrency) }
        }
        group("Automatic mode") {
            row { checkBox("Load the merge request of the new branch after a checkout").bindSelected(app::refreshOnBranchChange) }
            row("Check for new notes every (minutes, 0 = off):") { intTextField(0..600).bindIntText(app::pollMinutes) }
            row { checkBox("Make fix suggestions in the background after each load").bindSelected(app::suggestFixes) }
            row { checkBox("Make reply drafts in the background after each load").bindSelected(app::suggestReplies) }
            row { checkBox("Ask before Fix All applies the fixes").bindSelected(app::confirmFixAll) }
            row { checkBox("Show the notes in the editor under the line").bindSelected(app::showInlays) }
        }
        group("Pipeline") {
            row("Check a running pipeline every (seconds, 0 = off):") { intTextField(0..3600).bindIntText(app::pipelinePollSeconds) }
            row { checkBox("Notify when the pipeline fails or passes").bindSelected(app::pipelineNotify) }
            row { checkBox("Analyse failed jobs with opencode automatically").bindSelected(app::pipelineAutoExplain) }
            row("Failed jobs analysed automatically per pipeline:") { intTextField(1..50).bindIntText(app::pipelineMaxJobs) }
            row("End of the job log sent to opencode (KB):") { intTextField(4..10240).bindIntText(app::pipelineMaxLogKb) }
        }
        group("Self review") {
            row("Base branch:") { textField().bindText(ps::selfReviewBaseBranch).comment("Empty: the target branch of the merge request, or main.") }
            row("Maximum diff (KB):") { intTextField(10..10240).bindIntText(ps::selfReviewMaxDiffKb) }
        }
        group("Commit message format (this project)") {
            row("Template:") {
                textArea().rows(4).align(AlignX.FILL).bindText(ps::commitTemplate)
                    .comment("Fields: {type} {scope} {subject} {body} {breaking} {footer} {branch} {issue} {mr}. {?name:...} is kept only when the field is not empty. {{ gives a literal brace.")
            }
            row("Types (one per line, name: description):") { textArea().rows(6).align(AlignX.FILL).bindText({ ps.commitTypes.joinToString("\n") }, { ps.commitTypes = lines(it) }).comment("Empty: the message has no type.") }
            row("Scopes (one per line):") { textArea().rows(2).align(AlignX.FILL).bindText({ ps.commitScopes.joinToString("\n") }, { ps.commitScopes = lines(it) }).comment("Empty: opencode chooses a short scope or none.") }
            row("Subject length limit:") { intTextField(20..200).bindIntText(ps::commitSubjectMaxLength) }
            row("Subject first letter:") { comboBox(listOf("lower", "sentence", "any")).bindItem({ ps.commitSubjectCase }, { ps.commitSubjectCase = it ?: "lower" }) }
            row { checkBox("End the subject with a period").bindSelected(ps::commitSubjectPeriod) }
            row("Body:") { comboBox(listOf("never", "when-useful", "always")).bindItem({ ps.commitBody }, { ps.commitBody = it ?: "when-useful" }) }
            row("Body style:") { comboBox(listOf("paragraphs", "bullets")).bindItem({ ps.commitBodyStyle }, { ps.commitBodyStyle = it ?: "paragraphs" }) }
            row("Wrap the body at column (0 = off):") { intTextField(0..200).bindIntText(ps::commitBodyWrap) }
            row("Footer template:") { textArea().rows(2).align(AlignX.FILL).bindText(ps::commitFooter).comment("Filled by the plugin, not by opencode. Example: {?issue:Refs: #{issue}}{?mr:\\nSee !{mr}}") }
            row("Issue pattern:") { textField().align(AlignX.FILL).bindText(ps::commitIssuePattern).comment("Regular expression that finds {issue} in the branch name (the first group, or the whole match).") }
            row("Language:") { textField().bindText(ps::commitLanguage) }
            row("Extra rules:") { textArea().rows(3).align(AlignX.FILL).bindText(ps::commitRules) }
            row("Examples (separate them with a line ---):") {
                textArea().rows(4).align(AlignX.FILL).bindText(
                    { ps.commitExamples.joinToString("\n---\n") },
                    { ps.commitExamples = it.split(Regex("\\n---\\n")).map { e -> e.trim() }.filter { e -> e.isNotEmpty() }.toMutableList() },
                )
            }
            row("Changes to describe:") {
                comboBox(listOf("auto", "staged", "all")).bindItem({ ps.commitSource }, { ps.commitSource = it ?: "auto" })
                    .comment("auto: the files checked in the commit window; without them the staged changes, or all changes.")
            }
            row("Maximum diff (KB):") { intTextField(10..10240).bindIntText(ps::commitMaxDiffKb) }
            row("Recent commit subjects sent as a style example:") { intTextField(0..50).bindIntText(ps::commitRecentCommits) }
        }
    }

    private fun lines(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
}
