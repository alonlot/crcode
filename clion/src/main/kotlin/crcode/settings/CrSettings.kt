package crcode.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import crcode.core.BodyPolicy
import crcode.core.CommitFormat
import crcode.core.CommitLayout
import crcode.core.SubjectCase
import crcode.core.parseTypeEntry

/** IDE-wide settings: opencode and the automatic behavior. */
@Service(Service.Level.APP)
@State(name = "GitLabCrSettings", storages = [Storage("gitlabCr.xml")])
class CrSettings : PersistentStateComponent<CrSettings.State> {
    class State {
        var opencodePath = "opencode"
        var opencodeModel = ""
        var opencodeAgent = "plan"
        var opencodeTimeoutSeconds = 300
        var strictSSL = true
        var refreshOnBranchChange = true
        var pollMinutes = 5
        var suggestFixes = true
        var suggestReplies = true
        var concurrency = 2
        var confirmFixAll = true
        var pipelinePollSeconds = 60
        var pipelineNotify = true
        var pipelineAutoExplain = true
        var pipelineMaxJobs = 3
        var pipelineMaxLogKb = 48
        var showInlays = true
    }

    private var state = State()
    override fun getState() = state
    override fun loadState(s: State) {
        state = s
    }

    companion object {
        fun app(): CrSettings = ApplicationManager.getApplication().getService(CrSettings::class.java)
    }
}

const val DEFAULT_TEMPLATE =
    "{?type:{type}{?scope:({scope})}{?breaking:!}: }{subject}\n\n{?body:{body}\n\n}{?breaking:BREAKING CHANGE: {breaking}\n\n}{?footer:{footer}}"

val DEFAULT_TYPES = listOf(
    "feat: a new feature",
    "fix: a bug fix",
    "docs: documentation only",
    "style: formatting, no code change",
    "refactor: code change that is not a fix or a feature",
    "perf: performance",
    "test: tests only",
    "build: build system or dependencies",
    "ci: CI configuration",
    "chore: other changes that do not touch src or tests",
    "revert: reverts a commit",
)

/**
 * Project settings. They are kept in .idea/gitlabCrProject.xml, so a team can commit them
 * and share the GitLab project and the commit format.
 */
@Service(Service.Level.PROJECT)
@State(name = "GitLabCrProjectSettings", storages = [Storage("gitlabCrProject.xml")])
class CrProjectSettings : PersistentStateComponent<CrProjectSettings.State> {
    class State {
        var baseUrl = ""
        var projectPath = ""
        var remoteName = "origin"
        var showResolved = false
        var selfReviewBaseBranch = ""
        var selfReviewMaxDiffKb = 200

        var commitTemplate = DEFAULT_TEMPLATE
        var commitTypes: MutableList<String> = DEFAULT_TYPES.toMutableList()
        var commitScopes: MutableList<String> = mutableListOf()
        var commitSubjectMaxLength = 72
        var commitSubjectCase = "lower"
        var commitSubjectPeriod = false
        var commitBody = "when-useful"
        var commitBodyStyle = "paragraphs"
        var commitBodyWrap = 72
        var commitFooter = ""
        var commitIssuePattern = "(?:^|[/_-])([A-Z]+-\\d+|\\d+)(?=[/_-]|$)"
        var commitLanguage = "English"
        var commitRules = ""
        var commitExamples: MutableList<String> = mutableListOf()
        /** auto: the files checked in the commit window (or staged, or all); staged; all. */
        var commitSource = "auto"
        var commitMaxDiffKb = 200
        var commitRecentCommits = 5
    }

    private var state = State()
    override fun getState() = state
    override fun loadState(s: State) {
        state = s
    }

    fun commitFormat(): CommitFormat = with(state) {
        CommitFormat(
            types = commitTypes.map { parseTypeEntry(it) }.filter { it.name.isNotEmpty() },
            scopes = commitScopes.map { it.trim() }.filter { it.isNotEmpty() },
            subjectMaxLength = commitSubjectMaxLength,
            subjectCase = when (commitSubjectCase) {
                "sentence" -> SubjectCase.SENTENCE
                "any" -> SubjectCase.ANY
                else -> SubjectCase.LOWER
            },
            subjectPeriod = commitSubjectPeriod,
            body = when (commitBody) {
                "never" -> BodyPolicy.NEVER
                "always" -> BodyPolicy.ALWAYS
                else -> BodyPolicy.WHEN_USEFUL
            },
            bodyBullets = commitBodyStyle == "bullets",
            language = commitLanguage,
            rules = commitRules,
            examples = commitExamples,
        )
    }

    fun commitLayout() = CommitLayout(state.commitTemplate, state.commitFooter, state.commitIssuePattern, state.commitBodyWrap)

    companion object {
        fun of(project: Project): CrProjectSettings = project.getService(CrProjectSettings::class.java)
    }
}

/** Suggestions and drafts kept per workspace (not shared), so a restart does not ask opencode again. */
@Service(Service.Level.PROJECT)
@State(name = "GitLabCrCache", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class CrCache : PersistentStateComponent<CrCache.State> {
    class State {
        /** JSON of the cache entries, by key. */
        var entries: MutableMap<String, String> = mutableMapOf()
        var jobs: MutableMap<String, String> = mutableMapOf()
    }

    private var state = State()
    override fun getState() = state
    override fun loadState(s: State) {
        state = s
    }

    companion object {
        fun of(project: Project): CrCache = project.getService(CrCache::class.java)
    }
}

/** The GitLab token, in the IDE password safe (per GitLab URL). The GITLAB_TOKEN environment variable also works. */
object Tokens {
    private fun attributes(baseUrl: String) = CredentialAttributes(generateServiceName("GitLab CR", baseUrl.trimEnd('/')))

    fun get(baseUrl: String): String? =
        PasswordSafe.instance.getPassword(attributes(baseUrl))?.takeIf { it.isNotBlank() } ?: System.getenv("GITLAB_TOKEN")?.takeIf { it.isNotBlank() }

    fun set(baseUrl: String, token: String?) {
        PasswordSafe.instance.set(attributes(baseUrl), token?.let { Credentials("token", it) })
    }
}
