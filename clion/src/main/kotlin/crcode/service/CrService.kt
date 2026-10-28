package crcode.service

import com.google.gson.Gson
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import crcode.core.CiStatus
import crcode.core.CrNote
import crcode.core.CrThread
import crcode.core.EditException
import crcode.core.FixSuggestion
import crcode.core.OffsetRange
import crcode.core.PreparedEdit
import crcode.core.ThreadSource
import crcode.core.checkCommitParts
import crcode.core.cleanJobLog
import crcode.core.commitPrompt
import crcode.core.findOverlaps
import crcode.core.fixPrompt
import crcode.core.formatDuration
import crcode.core.locate
import crcode.core.mapLine
import crcode.core.numberLines
import crcode.core.parseHunks
import crcode.core.parseRemote
import crcode.core.pipelinePrompt
import crcode.core.prepareEdit
import crcode.core.previewText
import crcode.core.renderCommitMessage
import crcode.core.replyPrompt
import crcode.core.selfReviewPrompt
import crcode.core.statusLabel
import crcode.core.str
import crcode.core.stripBasePath
import crcode.core.validateFixAnswer
import crcode.git.Git
import crcode.gitlab.Discussion
import crcode.gitlab.GitLabClient
import crcode.gitlab.GitLabUser
import crcode.gitlab.Job
import crcode.gitlab.MergeRequest
import crcode.gitlab.Pipeline
import crcode.opencode.OpencodeNotFoundException
import crcode.opencode.OpencodeRunner
import crcode.settings.CrCache
import crcode.settings.CrProjectSettings
import crcode.settings.CrSettings
import crcode.settings.Tokens
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class PipelineInfo(val pipeline: Pipeline, val jobs: List<Job>)

class Session(
    val root: String,
    val project: String,
    val baseUrl: String,
    val client: GitLabClient,
    var me: GitLabUser? = null,
    var mr: MergeRequest? = null,
)

/** Something the user must fix (shown as an error message), as opposed to a bug. */
class UserException(message: String) : Exception(message)

class NoTokenException(val baseUrl: String) : Exception("A GitLab access token is needed for $baseUrl.")

private data class CacheEntry(
    val at: Long,
    val suggestion: FixSuggestion?,
    val draftReply: String?,
    val skipAutoFix: Boolean,
    val skipAutoReply: Boolean,
)

private data class JobCacheEntry(
    val at: Long,
    val body: String,
    val file: String?,
    val line: Int?,
    val suggestion: FixSuggestion?,
    val skipAutoFix: Boolean,
    val dismissed: Boolean,
)

interface CrListener {
    fun changed() {}
    fun showThread(t: CrThread) {}
    fun showPipeline() {}
}

@Service(Service.Level.PROJECT)
class CrService(val project: Project) : Disposable {
    private val logger = Logger.getInstance(CrService::class.java)
    private val gson = Gson()
    private val listeners = mutableListOf<CrListener>()
    val logText = StringBuilder()
    val opencode = OpencodeRunner { log(it) }

    @Volatile var session: Session? = null
        private set
    @Volatile var gitlabThreads: List<CrThread> = emptyList()
        private set
    @Volatile var selfThreads: List<CrThread> = emptyList()
        private set
    @Volatile var pipelineThreads: List<CrThread> = emptyList()
        private set
    @Volatile var pipeline: PipelineInfo? = null
        private set
    /** Text for the top of the tool window, for example "!7 Feature X (4 open notes)". */
    @Volatile var message: String = "No review notes loaded."
        private set
    @Volatile var loading = false
        private set

    val threads: List<CrThread> get() = gitlabThreads + selfThreads + pipelineThreads

    private var branch: String? = null
    private var gitDir: String? = null
    private var headText: String? = null
    private var remoteSignature: String? = null
    private var lastLoad = 0L
    private var lastPipelineLoad = 0L
    private var lastTick = 0L
    private val seenNotes = ConcurrentHashMap<Int, MutableSet<Long>>()
    private val missingShas = ConcurrentHashMap.newKeySet<String>()
    private var me: Pair<String, GitLabUser>? = null
    private val refreshing = AtomicBoolean(false)
    /** Each load gets a number; the results of an older load never replace the results of a newer one. */
    private val loadSeq = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var appliedSeq = 0L
    @Volatile private var refreshQueued = false
    @Volatile private var autoIndicator: ProgressIndicator? = null
    @Volatile private var pipelineIndicator: ProgressIndicator? = null
    private var timer: ScheduledFuture<*>? = null
    /** The opencode path that could not be started; the automatic runs wait until the path changes. */
    @Volatile private var missingOpencode: String? = null

    /** A successful answer means opencode works again (for example after it was installed). */
    private fun askOpencode(cwd: String, prompt: String, indicator: ProgressIndicator?) =
        opencode.askJson(cwd, prompt, indicator).also { missingOpencode = null }

    private fun opencodeUsable(): Boolean {
        val missing = missingOpencode ?: return true
        return missing != settings().opencodePath.ifBlank { "opencode" }
    }

    /** Tells the user once, and stops the background run: the other jobs would fail the same way. */
    private fun onOpencodeMissing(e: OpencodeNotFoundException, indicator: ProgressIndicator) {
        indicator.cancel()
        synchronized(this) {
            if (missingOpencode == e.path) return
            missingOpencode = e.path
        }
        notify(
            e.message + " The automatic suggestions are off until you change the path.",
            NotificationType.WARNING,
            "Open Settings" to { com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, "GitLab CR + opencode") },
        )
    }
    /** For tests: the diff request of the last preview. */
    @Volatile var lastPreview: Pair<CrThread, Map<String, String>>? = null

    fun addListener(l: CrListener, parent: Disposable) {
        synchronized(listeners) { listeners.add(l) }
        com.intellij.openapi.util.Disposer.register(parent) { synchronized(listeners) { listeners.remove(l) } }
    }

    private fun each(fn: (CrListener) -> Unit) {
        val copy = synchronized(listeners) { listeners.toList() }
        ui { copy.forEach(fn) }
    }

    private fun fireChanged() = each { it.changed() }

    fun log(line: String) {
        logger.info(line)
        synchronized(logText) {
            logText.append(line).append('\n')
            if (logText.length > 2_000_000) logText.delete(0, logText.length - 1_500_000)
        }
    }

    private fun ui(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) block() else app.invokeLater({ if (!project.isDisposed) block() }, ModalityState.any())
    }

    /** Runs on the EDT and waits, so the state is updated before the background work goes on. */
    private fun <T> onEdt(block: () -> T): T {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) return block()
        var result: Result<T>? = null
        app.invokeAndWait({ result = runCatching(block) }, ModalityState.any())
        return result!!.getOrThrow()
    }

    private fun settings() = CrSettings.app().state
    private fun projectSettings() = CrProjectSettings.of(project).state

    fun notify(text: String, type: NotificationType = NotificationType.INFORMATION, vararg actions: Pair<String, () -> Unit>) {
        val n = NotificationGroupManager.getInstance().getNotificationGroup("GitLab CR").createNotification(text, type)
        for ((title, fn) in actions) n.addAction(NotificationAction.createSimpleExpiring(title) { fn() })
        n.notify(project)
    }

    fun error(e: Throwable, what: String) {
        if (e is ProcessCanceledException) return
        if (e is NoTokenException) {
            notify(e.message!!, NotificationType.WARNING, "Set Token" to { ui { askToken(e.baseUrl) } })
            return
        }
        log("[$what] ${e.message}")
        if (e !is UserException && e !is EditException) logger.warn(what, e)
        notify(e.message ?: e.toString(), NotificationType.ERROR, "Show Log" to { ui { showLog() } })
    }

    /** Runs blocking work in a background task with a progress bar. */
    fun background(title: String, cancellable: Boolean = true, work: (ProgressIndicator) -> Unit) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, cancellable) {
            override fun run(indicator: ProgressIndicator) = work(indicator)
            override fun onThrowable(error: Throwable) = error(error, title)
        })
    }

    // ---------- lifecycle ----------

    fun start() {
        if (timer != null) return
        timer = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ tick() }, 3, 3, TimeUnit.SECONDS)
        val base = try {
            connectionInfo().second
        } catch (_: Exception) {
            null
        }
        if (base != null && Tokens.get(base) != null) refresh()
    }

    override fun dispose() {
        timer?.cancel(false)
        autoIndicator?.cancel()
        pipelineIndicator?.cancel()
    }

    private fun tick() {
        if (project.isDisposed) return
        try {
            checkHead()
            val s = session ?: return
            if (!ApplicationManager.getApplication().isActive || loading) return
            checkRemote(s)
            val now = System.currentTimeMillis()
            if (now - lastTick < 30_000) return
            lastTick = now
            val seconds = settings().pipelinePollSeconds
            val p = pipeline
            if (seconds > 0 && p != null && CiStatus.isActive(p.pipeline.status) && now - lastPipelineLoad >= seconds * 1000L) {
                loadPipeline(s)
            }
            val minutes = settings().pollMinutes
            if (minutes > 0 && now - lastLoad >= minutes * 60_000L) {
                lastLoad = now
                refresh()
            }
        } catch (e: Exception) {
            log("Periodic check failed: ${e.message}")
        }
    }

    private fun checkHead() {
        val dir = gitDir ?: return
        val text = try {
            File(dir, "HEAD").readText().trim()
        } catch (_: Exception) {
            return
        }
        if (headText == null) {
            headText = text
            return
        }
        if (text == headText) return
        headText = text
        if (!settings().refreshOnBranchChange) return
        val root = session?.root ?: return
        val now = Git.currentBranch(root)
        if (now == branch || now == "HEAD") return
        log("Branch changed: $branch -> $now. Loading its merge request.")
        branch = now
        refresh()
    }

    /** A push updates the remote-tracking ref; a new pipeline usually starts then. */
    private fun checkRemote(s: Session) {
        val dir = gitDir ?: return
        val mr = s.mr ?: return
        val remote = projectSettings().remoteName.ifBlank { "origin" }
        val sig = listOf(File(dir, "refs/remotes/$remote/${mr.sourceBranch}"), File(dir, "packed-refs")).joinToString { "${it.lastModified()}" }
        if (remoteSignature != null && sig != remoteSignature) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule({ loadPipeline(s) }, 3, TimeUnit.SECONDS)
        }
        remoteSignature = sig
    }

    // ---------- connection ----------

    fun repoRoot(): String {
        val base = project.basePath ?: throw UserException("Open a folder that contains a git repository.")
        return try {
            Git.repoRoot(base)
        } catch (e: Exception) {
            throw UserException("The project folder is not in a git repository: ${e.message}")
        }
    }

    /** Root, base URL and project path, from the settings or the git remote. */
    fun connectionInfo(): Triple<String, String, String> {
        val ps = projectSettings()
        val root = repoRoot()
        val remote = ps.remoteName.ifBlank { "origin" }
        var baseUrl = ps.baseUrl.trim()
        var path = ps.projectPath.trim()
        if (baseUrl.isEmpty() || path.isEmpty()) {
            val parsed = parseRemote(Git.remoteUrl(root, remote))
                ?: throw UserException("Cannot read the GitLab project from remote '$remote'. Set the GitLab URL and project in Settings | Tools | GitLab CR.")
            if (baseUrl.isEmpty()) baseUrl = parsed.guessedBaseUrl
            if (path.isEmpty()) path = stripBasePath(parsed.path, baseUrl)
        }
        return Triple(root, baseUrl, path)
    }

    private fun connect(): Session {
        val (root, baseUrl, path) = connectionInfo()
        val token = Tokens.get(baseUrl) ?: throw NoTokenException(baseUrl)
        return Session(root, path, baseUrl, GitLabClient(baseUrl, token, settings().strictSSL))
    }

    fun askToken(baseUrl: String? = null) {
        val url = baseUrl ?: try {
            connectionInfo().second
        } catch (e: Exception) {
            error(e, "Set token")
            return
        }
        val token = Messages.showPasswordDialog(
            project,
            "Personal access token for $url.\nIt needs the \"api\" scope. It is kept in the IDE password safe.",
            "GitLab Access Token",
            null,
        ) ?: return
        if (token.isBlank()) return
        Tokens.set(url, token.trim())
        notify("GitLab token saved.")
        refresh()
    }

    // ---------- load notes ----------

    /** Only one load runs at a time. A call made during a load runs one more load after it (the branch can have changed). */
    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            refreshQueued = true
            return
        }
        background("Loading GitLab CR notes") { indicator ->
            try {
                loadBlocking(indicator)
            } finally {
                refreshing.set(false)
                if (refreshQueued) {
                    refreshQueued = false
                    refresh()
                }
            }
        }
    }

    /** Loads the notes and the pipeline. Blocks; public for tests. */
    fun loadBlocking(indicator: ProgressIndicator?) {
        val seq = loadSeq.incrementAndGet()
        loading = true
        fireChanged()
        try {
            val s = connect()
            gitDir = Git.gitDir(s.root)
            if (headText == null) headText = runCatching { File(gitDir!!, "HEAD").readText().trim() }.getOrNull()
            val br = Git.currentBranch(s.root)
            branch = br
            s.mr = s.client.findMergeRequest(s.project, br)
            s.me = currentUser(s)
            indicator?.checkCanceled()
            val discussions = s.mr?.let { s.client.discussions(s.project, it.iid) } ?: emptyList()
            val showResolved = projectSettings().showResolved
            val fresh = discussions.mapNotNull { toThread(it) }.filter { showResolved || !it.resolved }
            mapToLocalLines(s, fresh)
            val newNotes = s.mr?.let { countNewNotes(it.iid, fresh, s.me) } ?: 0
            val cache = CrCache.of(project).state.entries
            val applied = onEdt {
                if (seq < appliedSeq) {
                    log("A newer load finished first; the results of load $seq are not used.")
                    return@onEdt false
                }
                appliedSeq = seq
                val previous = gitlabThreads.associateBy { it.id }
                session = s
                gitlabThreads = fresh.map { t ->
                    val old = previous[t.id]
                    if (old != null && old.sameAs(t)) return@map old
                    val saved: CacheEntry? = if (old != null && old.cacheKey == t.cacheKey) {
                        CacheEntry(0, old.suggestion, old.draftReply, old.skipAutoFix, old.skipAutoReply)
                    } else {
                        cache[t.cacheKey]?.let { runCatching { gson.fromJson(it, CacheEntry::class.java) }.getOrNull() }
                    }
                    t.suggestion = saved?.suggestion
                    t.draftReply = saved?.draftReply
                    t.skipAutoFix = saved?.skipAutoFix ?: false
                    t.skipAutoReply = saved?.skipAutoReply ?: false
                    t
                }
                val mr = s.mr
                message = if (mr == null) {
                    "No open merge request for branch '$br' in ${s.project}."
                } else {
                    val open = gitlabThreads.count { !it.resolved }
                    "!${mr.iid} ${mr.title} ($open open note${if (open == 1) "" else "s"})"
                }
                lastLoad = System.currentTimeMillis()
                true
            }
            if (!applied) return
            if (newNotes > 0) {
                notify("$newNotes new review note(s) on !${s.mr!!.iid}.", NotificationType.INFORMATION, "Show" to { each { it.changed() } })
            }
            loadPipelineBlocking(s)
        } finally {
            loading = false
            fireChanged()
        }
        if (seq == appliedSeq) startAutoSuggest()
    }

    private fun currentUser(s: Session): GitLabUser? {
        val cached = me
        if (cached?.first == s.baseUrl) return cached.second
        return try {
            s.client.currentUser().also { me = s.baseUrl to it }
        } catch (e: Exception) {
            log("Cannot read the current GitLab user: ${e.message}")
            null
        }
    }

    /**
     * GitLab gives line numbers of the pushed commit. Moves them to the lines of the local files
     * (with `git diff <commit>` for each file) and marks notes whose line was changed as outdated.
     */
    private fun mapToLocalLines(s: Session, threads: List<CrThread>) {
        val remote = projectSettings().remoteName.ifBlank { "origin" }
        val shas = HashMap<String, Boolean>()
        var fetched = false
        fun hasSha(sha: String): Boolean = shas.getOrPut(sha) {
            var ok = Git.hasCommit(s.root, sha)
            val mr = s.mr
            if (!ok && mr != null && !fetched && sha !in missingShas) {
                fetched = true
                try {
                    Git.fetchBranch(s.root, remote, mr.sourceBranch)
                } catch (e: Exception) {
                    log("Cannot fetch ${mr.sourceBranch}: ${e.message}")
                }
                ok = Git.hasCommit(s.root, sha)
            }
            if (!ok) missingShas.add(sha)
            ok
        }
        val diffs = HashMap<String, String>()
        for (t in threads) {
            val file = t.file ?: continue
            val gl = t.gitlabLine ?: continue
            val sha = t.positionSha ?: continue
            if (!hasSha(sha)) {
                log("Commit ${sha.take(8)} is not available locally; ${t.location} is not moved.")
                continue
            }
            val diff = diffs.getOrPut("$sha:$file") { runCatching { Git.diffFileSince(s.root, sha, file) }.getOrDefault("") }
            val mapped = mapLine(parseHunks(diff), gl)
            t.line = mapped.line
            t.outdated = mapped.outdated
        }
    }

    private fun countNewNotes(iid: Int, threads: List<CrThread>, me: GitLabUser?): Int {
        val ids = threads.flatMap { t -> t.notes.filter { it.authorId != me?.id }.map { it.id ?: -1 } }
        val seen = seenNotes[iid]
        seenNotes[iid] = ((seen ?: emptySet()) + ids).toMutableSet()
        return if (seen == null) 0 else ids.count { it !in seen }
    }

    private fun isMine(t: CrThread): Boolean {
        val me = session?.me ?: return false
        return t.notes.lastOrNull()?.authorId == me.id
    }

    /** Called after a thread got a result: moves it to the live thread object (a load can replace it) and saves the cache. */
    fun update(changed: CrThread?) {
        if (changed != null) {
            var t: CrThread = changed
            val live = gitlabThreads.find { it.id == changed.id && it !== changed && it.cacheKey == changed.cacheKey }
            if (live != null) {
                live.suggestion = changed.suggestion
                live.draftReply = changed.draftReply
                live.skipAutoFix = changed.skipAutoFix
                live.skipAutoReply = changed.skipAutoReply
                t = live
            }
            saveCache(t)
        }
        fireChanged()
    }

    private fun saveCache(t: CrThread) {
        val cache = CrCache.of(project).state
        val minAge = System.currentTimeMillis() - CACHE_DAYS * 24 * 3600 * 1000L
        when (t.source) {
            ThreadSource.GITLAB -> {
                cache.entries.entries.removeIf { runCatching { gson.fromJson(it.value, CacheEntry::class.java).at < minAge }.getOrDefault(true) }
                cache.entries[t.cacheKey] = gson.toJson(CacheEntry(System.currentTimeMillis(), t.suggestion, t.draftReply, t.skipAutoFix, t.skipAutoReply))
            }
            ThreadSource.PIPELINE -> saveJobCache(t, false)
            ThreadSource.SELF -> {}
        }
    }

    // ---------- automatic suggestions ----------

    fun startAutoSuggest() {
        autoIndicator?.cancel()
        val s = settings()
        val jobs = mutableListOf<Pair<CrThread, Boolean>>() // true = fix
        for (t in gitlabThreads.filter { !it.resolved && !isMine(it) }) {
            // An outdated note is often already fixed; its fix is made only on request.
            if (s.suggestFixes && t.file != null && t.line != null && !t.outdated && t.suggestion == null && !t.skipAutoFix) jobs.add(t to true)
            if (s.suggestReplies && t.draftReply == null && !t.skipAutoReply) jobs.add(t to false)
        }
        if (jobs.isEmpty() || !opencodeUsable()) return
        val mr = session?.mr
        background("opencode: preparing CR suggestions") { indicator ->
            autoIndicator = indicator
            var fixes = 0
            var replies = 0
            var failed = 0
            var done = 0
            runPool(jobs, s.concurrency, indicator) { (t, isFix) ->
                try {
                    if (isFix) {
                        generateFix(t, indicator); synchronized(this) { fixes++ }
                    } else {
                        generateReply(t, indicator); synchronized(this) { replies++ }
                    }
                    ui { update(t) }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: OpencodeNotFoundException) {
                    onOpencodeMissing(e, indicator)
                } catch (e: Exception) {
                    synchronized(this) { failed++ }
                    log("Automatic ${if (isFix) "fix" else "reply"} for ${t.location} failed: ${e.message}")
                }
                synchronized(this) { done++ }
                indicator.text2 = "$done/${jobs.size}"
                indicator.fraction = done.toDouble() / jobs.size
            }
            if (indicator.isCanceled || (fixes == 0 && replies == 0 && failed == 0)) return@background
            notify(
                "opencode prepared $fixes fix(es) and $replies reply draft(s)" + (mr?.let { " for !${it.iid}" } ?: "") +
                    if (failed > 0) ". $failed failed (see the GitLab CR log)." else ".",
            )
        }
    }

    /** Runs `fn` for each item, at most `concurrency` at a time, until the indicator is cancelled. */
    private fun <T> runPool(items: List<T>, concurrency: Int, indicator: ProgressIndicator, fn: (T) -> Unit) {
        indicator.isIndeterminate = false
        val pool = AppExecutorUtil.createBoundedApplicationPoolExecutor("GitLabCr", maxOf(1, concurrency))
        val futures = items.map { item ->
            pool.submit(Callable {
                if (!indicator.isCanceled) {
                    try {
                        fn(item)
                    } catch (_: ProcessCanceledException) {
                    }
                }
            })
        }
        for (f in futures) {
            while (true) {
                try {
                    f.get(200, TimeUnit.MILLISECONDS)
                    break
                } catch (_: java.util.concurrent.TimeoutException) {
                    if (indicator.isCanceled) break
                }
            }
        }
        pool.shutdown()
    }

    // ---------- files ----------

    fun resolveInRoot(root: String, rel: String): File {
        val abs = File(root, rel).canonicalFile
        val base = File(root).canonicalFile
        if (abs == base || !abs.path.startsWith(base.path + File.separator)) throw UserException("Refusing to edit '$rel': it is outside the repository")
        return abs
    }

    fun virtualFile(root: String, rel: String): VirtualFile =
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(resolveInRoot(root, rel)) ?: throw UserException("$rel does not exist")

    fun document(root: String, rel: String): Document {
        val vf = virtualFile(root, rel)
        return ApplicationManager.getApplication().runReadAction(com.intellij.openapi.util.Computable { FileDocumentManager.getInstance().getDocument(vf) })
            ?: throw UserException("Cannot open $rel as text")
    }

    private fun text(doc: Document): String = ApplicationManager.getApplication().runReadAction(com.intellij.openapi.util.Computable { doc.text })

    private fun root(): String = session?.root ?: repoRoot()

    // ---------- fixes ----------

    fun generateFix(t: CrThread, indicator: ProgressIndicator?) {
        val root = root()
        val file = t.file ?: throw UserException("This note is not attached to a line of code.")
        val line = t.line ?: throw UserException("This note is not attached to a line of code.")
        val lines = text(document(root, file)).split("\n")
        val start = maxOf(1, line - CONTEXT_LINES)
        val end = minOf(lines.size, line + CONTEXT_LINES)
        val prompt = fixPrompt(t, numberLines(lines.subList(start - 1, end), start), start, end)
        val answer = validateFixAnswer(askOpencode(root, prompt, indicator))
        val edits = answer.edits.map { e -> prepareEdit(text(document(root, e.file)), e) }
        t.suggestion = FixSuggestion(answer.explanation, edits)
    }

    fun suggestFix(t: CrThread) {
        background("opencode: fixing \"${t.title.take(60)}\"") { indicator ->
            generateFix(t, indicator)
            ui {
                update(t)
                openThread(t)
                t.suggestion?.takeIf { it.edits.isEmpty() }?.let { notify("opencode suggests no code change: ${it.explanation}") }
            }
        }
    }

    data class ApplyResult(val applied: List<CrThread>, val failed: List<Pair<CrThread, String>>)

    /**
     * Applies the edits of many threads in one undoable command (EDT only).
     * A thread is applied only when all its edits can be located and none overlaps an edit of another thread.
     */
    fun applyGrouped(groups: List<Pair<CrThread, List<PreparedEdit>>>): ApplyResult {
        val root = root()
        val failed = mutableListOf<Pair<CrThread, String>>()
        class Located(val owner: Int, val doc: Document, val range: OffsetRange, val text: String)
        val candidates = mutableListOf<Pair<Int, List<Located>>>()
        groups.forEachIndexed { gi, (t, edits) ->
            try {
                candidates.add(gi to edits.map { e ->
                    val doc = document(root, e.file)
                    Located(gi, doc, locate(doc.text, e), e.replacement)
                })
            } catch (e: Exception) {
                failed.add(t to (e.message ?: e.toString()))
            }
        }
        val rejected = mutableSetOf<Int>()
        for (list in candidates.flatMap { it.second }.groupBy { it.doc }.values) {
            for (i in findOverlaps(list.map { it.range })) rejected.add(list[i].owner)
        }
        val accepted = candidates.filter { it.first !in rejected }
        candidates.filter { it.first in rejected }.forEach { failed.add(groups[it.first].first to "its edit overlaps another fix; apply it alone after the others") }
        if (accepted.isNotEmpty()) {
            WriteCommandAction.runWriteCommandAction(project, "Apply CR Fix", "gitlabCr", {
                for ((doc, items) in accepted.flatMap { it.second }.groupBy { it.doc }) {
                    for (it in items.sortedByDescending { it.range.start }) doc.replaceString(it.range.start, it.range.end, it.text)
                }
            })
        }
        return ApplyResult(accepted.map { groups[it.first].first }, failed)
    }

    fun applyFix(t: CrThread) {
        val s = t.suggestion
        if (s == null) {
            suggestFix(t)
            return
        }
        if (s.edits.isEmpty()) {
            notify("This suggestion has no code change to apply.")
            return
        }
        val result = applyGrouped(listOf(t to s.edits))
        if (result.failed.isNotEmpty()) throw UserException(result.failed[0].second)
        t.suggestion = null
        t.skipAutoFix = true
        update(t)
        com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)?.info = "Fix applied. Review and save the file."
    }

    fun discardFix(t: CrThread) {
        t.suggestion = null
        t.skipAutoFix = true
        update(t)
    }

    /** Shows the suggested fix in the diff viewer: current file on the left, file after the fix on the right, with an Apply button. */
    fun previewFix(t: CrThread) {
        val s = t.suggestion ?: run {
            suggestFix(t)
            return
        }
        if (s.edits.isEmpty()) return
        val root = root()
        val texts = LinkedHashMap<String, String>()
        for ((file, edits) in s.edits.groupBy { it.file }) texts[file] = previewText(document(root, file).text, edits)
        lastPreview = t to texts
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val factory = DiffContentFactory.getInstance()
        for ((file, after) in texts) {
            val vf = virtualFile(root, file)
            val request = SimpleDiffRequest(
                "${vf.name} ↔ opencode fix",
                factory.create(project, document(root, file)),
                factory.create(project, after, vf.fileType),
                "Current",
                "After the fix",
            )
            val apply = object : AnAction("Apply Fix", "Apply the opencode fix to the file", AllIcons.Actions.Checked) {
                override fun actionPerformed(e: AnActionEvent) {
                    try {
                        applyFix(t)
                    } catch (ex: Exception) {
                        error(ex, "Apply fix")
                    }
                }
            }
            request.putUserData(DiffUserDataKeysEx.CONTEXT_ACTIONS, listOf(apply))
            DiffManager.getInstance().showDiff(project, request)
        }
    }

    /** "Fix CR for all page": makes and applies fixes for every open note. */
    fun fixAll(pool: List<CrThread>) {
        val targets = pool.filter { it.file != null && it.line != null && !it.resolved && !it.skipAutoFix }
        if (targets.isEmpty()) {
            notify("There are no open notes on code lines to fix.")
            return
        }
        background("opencode: fixing all CR notes") { indicator ->
            val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
            val todo = targets.filter { it.suggestion == null }
            var done = 0
            runPool(todo, settings().concurrency, indicator) { t ->
                try {
                    generateFix(t, indicator)
                    ui { update(t) }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: OpencodeNotFoundException) {
                    indicator.cancel()
                    ui { error(e, "Fix All") }
                } catch (e: Exception) {
                    errors.add("${t.location}: ${e.message}")
                }
                synchronized(this) { done++ }
                indicator.text2 = "$done/${todo.size}"
                indicator.fraction = done.toDouble() / maxOf(1, todo.size)
            }
            indicator.checkCanceled()
            errors.forEach { log("Fix failed: $it") }
            ui { finishFixAll(targets, errors.size) }
        }
    }

    fun finishFixAll(targets: List<CrThread>, errors: Int) {
        val ready = targets.filter { it.suggestion?.edits?.isNotEmpty() == true }
        val noChange = targets.count { it.suggestion != null && it.suggestion!!.edits.isEmpty() }
        if (ready.isEmpty()) {
            notify("No fixes to apply. $noChange note(s) need no code change, $errors failed (see the GitLab CR log).", NotificationType.WARNING)
            return
        }
        if (settings().confirmFixAll) {
            val choice = Messages.showYesNoCancelDialog(
                project,
                "opencode prepared fixes for ${ready.size} of ${targets.size} notes" +
                    (if (noChange > 0) ", $noChange need no code change" else "") +
                    (if (errors > 0) ", $errors failed" else "") + ". Apply all fixes now?",
                "Fix All CR Notes",
                "Apply All",
                "Review One by One",
                "Cancel",
                Messages.getQuestionIcon(),
            )
            if (choice == Messages.NO) {
                openThread(ready[0])
                return
            }
            if (choice != Messages.YES) return
        }
        val result = applyGrouped(ready.map { it to it.suggestion!!.edits })
        for (t in result.applied) {
            t.suggestion = null
            t.skipAutoFix = true
            update(t)
        }
        result.failed.forEach { log("Not applied ${it.first.location}: ${it.second}") }
        val msg = "Applied ${result.applied.size} fix(es). Review and save the files."
        if (result.failed.isNotEmpty()) notify("$msg ${result.failed.size} not applied (see the GitLab CR log).", NotificationType.WARNING, "Show Log" to { showLog() })
        else notify(msg)
    }

    // ---------- replies ----------

    private fun requireGitLab(t: CrThread): Triple<Session, MergeRequest, String> {
        val s = session
        val mr = s?.mr
        val d = t.discussionId
        if (t.source != ThreadSource.GITLAB || d == null || s == null || mr == null) throw UserException("This note is not a GitLab discussion.")
        return Triple(s, mr, d)
    }

    /** Blocking. */
    fun postReplyBlocking(t: CrThread, body: String) {
        val (s, mr, d) = requireGitLab(t)
        val note = s.client.addNote(s.project, mr.iid, d, body)
        onEdt {
            t.notes.add(CrNote(note.id, note.author.id, note.author.name, note.author.avatarUrl, note.body, note.createdAt))
            update(t)
        }
    }

    fun postReply(t: CrThread, body: String) {
        if (body.isBlank()) return
        background("Sending reply to GitLab", cancellable = false) { postReplyBlocking(t, body.trim()) }
    }

    fun generateReply(t: CrThread, indicator: ProgressIndicator?) {
        val root = root()
        var context: String? = null
        val file = t.file
        val line = t.line
        if (file != null && line != null) {
            try {
                val lines = text(document(root, file)).split("\n")
                val start = maxOf(1, line - 15)
                context = numberLines(lines.subList(start - 1, minOf(lines.size, line + 15)), start)
            } catch (_: Exception) {
                // the file may not exist locally; opencode can still answer from the conversation
            }
        }
        val answer = askOpencode(root, replyPrompt(t, context), indicator)
        val reply = answer.takeIf { it.isJsonObject }?.asJsonObject?.str("reply")?.trim()
        if (reply.isNullOrEmpty()) throw UserException("opencode returned an empty reply.")
        t.draftReply = reply
    }

    fun suggestReply(t: CrThread) {
        background("opencode: writing a reply") { indicator ->
            generateReply(t, indicator)
            ui {
                update(t)
                each { it.showThread(t) }
            }
        }
    }

    fun sendDraft(t: CrThread, text: String) {
        if (text.isBlank()) return
        t.draftReply = null
        postReply(t, text)
    }

    fun discardDraft(t: CrThread) {
        t.draftReply = null
        t.skipAutoReply = true
        update(t)
    }

    fun saveDraft(t: CrThread, text: String) {
        if (t.draftReply != null && t.draftReply != text) {
            t.draftReply = text
            update(t)
        }
    }

    fun toggleResolved(t: CrThread) {
        val (s, mr, d) = requireGitLab(t)
        background("Updating the discussion", cancellable = false) {
            s.client.setResolved(s.project, mr.iid, d, !t.resolved)
            ui {
                t.resolved = !t.resolved
                update(t)
            }
        }
    }

    fun openThread(t: CrThread) {
        each { it.showThread(t) }
        val file = t.file ?: return
        val root = session?.root ?: return
        val vf = try {
            virtualFile(root, file)
        } catch (_: Exception) {
            return
        }
        val line = maxOf(0, (t.line ?: 1) - 1)
        OpenFileDescriptor(project, vf, line, 0).navigate(true)
    }

    fun openInBrowser() {
        val mr = session?.mr
        if (mr != null) BrowserUtil.browse(mr.webUrl) else notify("No open merge request for the current branch.")
    }

    // ---------- self review ----------

    fun selfReview() {
        background("opencode: self code review") { indicator ->
            selfReviewBlocking(indicator)
        }
    }

    fun selfReviewBlocking(indicator: ProgressIndicator?) {
        val root = root()
        val ps = projectSettings()
        val remote = ps.remoteName.ifBlank { "origin" }
        val baseBranch = ps.selfReviewBaseBranch.ifBlank { session?.mr?.targetBranch ?: "main" }
        val base = Git.mergeBase(root, baseBranch, remote)
        var diff = Git.diffSince(root, base)
        if (diff.isBlank()) {
            notify("There are no changes compared to '$baseBranch'.")
            return
        }
        val maxBytes = maxOf(10, ps.selfReviewMaxDiffKb) * 1024
        val bytes = diff.toByteArray()
        val truncated = bytes.size > maxBytes
        if (truncated) diff = String(bytes, 0, maxBytes)
        indicator?.text = "opencode: reviewing changes against $baseBranch"
        val answer = askOpencode(root, selfReviewPrompt(baseBranch, diff, truncated), indicator)
        val findings = answer.takeIf { it.isJsonObject }?.asJsonObject?.get("findings")?.takeIf { it.isJsonArray }?.asJsonArray ?: com.google.gson.JsonArray()
        var n = 0
        val list = findings.mapNotNull { el ->
            val f = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val file = f.str("file")?.replace(Regex("^[ab]/"), "")?.removePrefix("./") ?: return@mapNotNull null
            val comment = f.str("comment") ?: return@mapNotNull null
            val abs = try {
                resolveInRoot(root, file)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            val exists = abs.exists()
            val line = f.get("line")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            val severity = f.str("severity")?.lowercase()
            CrThread(
                "self-${System.currentTimeMillis()}-${n++}",
                ThreadSource.SELF,
                mutableListOf(CrNote(author = "opencode self review", body = (severity?.let { "**[$it]** " } ?: "") + comment)),
                if (exists) file else null,
                if (exists) line else null,
                severity = severity,
            )
        }
        onEdt {
            selfThreads = list
            fireChanged()
        }
        if (list.isEmpty()) notify("Self review: opencode found no issues.")
        else notify("Self review: ${list.size} finding(s). See the GitLab CR tool window.")
    }

    fun clearSelfReview() {
        selfThreads = emptyList()
        fireChanged()
    }

    fun dismissFinding(t: CrThread) {
        selfThreads = selfThreads.filter { it !== t }
        if (t.source == ThreadSource.PIPELINE) {
            pipelineThreads = pipelineThreads.filter { it !== t }
            saveJobCache(t, true)
        }
        fireChanged()
    }

    fun postFinding(t: CrThread) {
        if (t.source != ThreadSource.SELF) return
        background("Posting the finding to the merge request", cancellable = false) {
            val s = session ?: connect()
            val mr = s.mr ?: throw UserException("No open merge request for the current branch.")
            val body = t.notes.joinToString("\n\n") { it.body }
            val refs = mr.diffRefs
            var posted = false
            if (t.file != null && t.line != null && refs != null) {
                try {
                    s.client.createDiscussion(
                        s.project, mr.iid, body,
                        mapOf(
                            "position_type" to "text", "base_sha" to refs.baseSha, "start_sha" to refs.startSha, "head_sha" to refs.headSha,
                            "old_path" to t.file, "new_path" to t.file, "new_line" to t.line!!,
                        ),
                    )
                    posted = true
                } catch (e: Exception) {
                    // GitLab accepts a line position only on lines that are part of the pushed diff.
                    log("Cannot post on the line, posting as a general note: ${e.message}")
                }
            }
            if (!posted) s.client.createDiscussion(s.project, mr.iid, if (t.file != null) "`${t.location}`\n\n$body" else body)
            onEdt {
                selfThreads = selfThreads.filter { it !== t }
                fireChanged()
            }
            refresh()
        }
    }

    // ---------- commit message ----------

    /** Writes the commit message for the files (or the staged changes). Blocking. */
    fun writeCommitMessageBlocking(files: List<String>?, indicator: ProgressIndicator?): String {
        val root = repoRoot()
        val ps = CrProjectSettings.of(project)
        val st = ps.state
        val (diff, stat) = Git.changesForCommit(root, st.commitSource, if (st.commitSource == "auto") files else null)
        if (diff.isBlank()) {
            throw UserException(if (st.commitSource == "staged") "There are no staged changes." else "There are no changes to commit.")
        }
        val maxBytes = maxOf(10, st.commitMaxDiffKb) * 1024
        val bytes = diff.toByteArray()
        val truncated = bytes.size > maxBytes
        val shown = if (truncated) String(bytes, 0, maxBytes) else diff
        val br = Git.currentBranch(root)
        val recent = Git.recentSubjects(root, st.commitRecentCommits)
        val format = ps.commitFormat()
        var prompt = commitPrompt(format, shown, stat, br, recent, truncated)
        var result = checkCommitParts(askOpencode(root, prompt, indicator), format)
        if (result.problems.isNotEmpty()) {
            // One more try with the problems listed; after that the best answer is used.
            log("Commit message does not follow the format, asking again: " + result.problems.joinToString("; "))
            prompt += "\n\nYour previous answer had these problems, fix them:\n- " + result.problems.joinToString("\n- ")
            val second = checkCommitParts(askOpencode(root, prompt, indicator), format)
            if (second.problems.size <= result.problems.size) result = second
            if (result.problems.isNotEmpty()) notify("The commit message does not fully follow the format: " + result.problems.joinToString("; "), NotificationType.WARNING)
        }
        return renderCommitMessage(result.parts, ps.commitLayout(), br, session?.mr?.iid)
    }

    // ---------- pipeline ----------

    private fun saveJobCache(t: CrThread, dismissed: Boolean) {
        val cache = CrCache.of(project).state.jobs
        val minAge = System.currentTimeMillis() - CACHE_DAYS * 24 * 3600 * 1000L
        cache.entries.removeIf { runCatching { gson.fromJson(it.value, JobCacheEntry::class.java).at < minAge }.getOrDefault(true) }
        cache[t.id] = gson.toJson(JobCacheEntry(System.currentTimeMillis(), t.notes.firstOrNull()?.body ?: "", t.file, t.line, t.suggestion, t.skipAutoFix, dismissed))
    }

    private fun readJobCache(id: String): JobCacheEntry? =
        CrCache.of(project).state.jobs[id]?.let { runCatching { gson.fromJson(it, JobCacheEntry::class.java) }.getOrNull() }

    fun loadPipeline(s: Session) {
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                loadPipelineBlocking(s)
            } catch (e: Exception) {
                log("Cannot load the pipeline: ${e.message}")
            }
        }
    }

    /** Loads the latest pipeline of the merge request. Errors go to the log only; the notes are still useful without it. */
    fun loadPipelineBlocking(s: Session) {
        lastPipelineLoad = System.currentTimeMillis()
        val previous = pipeline
        val mr = s.mr
        val info = try {
            if (mr == null) null
            else s.client.mergeRequestPipelines(s.project, mr.iid).firstOrNull()?.let { latest ->
                val details = runCatching { s.client.pipeline(s.project, latest.id) }.getOrDefault(latest)
                PipelineInfo(details.copy(webUrl = details.webUrl.ifEmpty { latest.webUrl }), s.client.pipelineJobs(s.project, latest.id))
            }
        } catch (e: Exception) {
            log("Cannot load the pipeline: ${e.message}")
            return
        }
        onEdt {
            pipeline = info
            if (info == null || previous?.pipeline?.id != info.pipeline.id) {
                // A new pipeline: the findings of the old one are not valid any more.
                clearPipelineFindings()
            }
            fireChanged()
        }
        if (info == null) return
        val changed = previous?.pipeline?.id != info.pipeline.id || previous.pipeline.status != info.pipeline.status
        if (!changed || CiStatus.isActive(info.pipeline.status)) return
        val failed = failedJobs(info)
        if (info.pipeline.status == "failed" && failed.isNotEmpty()) {
            if (settings().pipelineNotify && previous != null) {
                notify(
                    "Pipeline #${info.pipeline.id} failed: ${failed.joinToString(", ") { it.name }}.",
                    NotificationType.ERROR,
                    "Explain and Fix" to { startAutoExplain(failed, true) },
                    "Show Pipeline" to { each { it.showPipeline() } },
                )
            }
            if (settings().pipelineAutoExplain) startAutoExplain(failed, false)
        } else if (info.pipeline.status == "success" && previous != null && settings().pipelineNotify) {
            ui { com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)?.info = "Pipeline #${info.pipeline.id} passed." }
        }
    }

    fun refreshPipeline() {
        val s = session
        if (s == null) refresh() else loadPipeline(s)
    }

    fun clearPipelineFindings() {
        pipelineIndicator?.cancel()
        if (pipelineThreads.isNotEmpty()) {
            pipelineThreads = emptyList()
            fireChanged()
        }
    }

    /** Runs opencode for the failed jobs in the background. Jobs explained before come from the cache. */
    fun startAutoExplain(jobs: List<Job>, explicit: Boolean) {
        pipelineIndicator?.cancel()
        val max = if (explicit) Int.MAX_VALUE else settings().pipelineMaxJobs
        val todo = mutableListOf<Job>()
        for (j in jobs) {
            val id = "pipeline-${j.id}"
            val saved = readJobCache(id)
            if (saved != null && (!saved.dismissed || explicit)) {
                if (pipelineThreads.none { it.id == id }) ui { addPipelineThread(j, saved.body, saved.file, saved.line, saved.suggestion, saved.skipAutoFix) }
            } else if (saved == null && todo.size < max) {
                todo.add(j)
            }
        }
        if (todo.isEmpty() || (!explicit && !opencodeUsable())) return
        background("opencode: analysing the failed pipeline") { indicator ->
            pipelineIndicator = indicator
            var ok = 0
            var failed = 0
            runPool(todo, settings().concurrency, indicator) { j ->
                try {
                    explainJobBlocking(j, indicator)
                    synchronized(this) { ok++ }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: OpencodeNotFoundException) {
                    onOpencodeMissing(e, indicator)
                } catch (e: Exception) {
                    synchronized(this) { failed++ }
                    log("Cannot analyse job ${j.name}: ${e.message}")
                }
            }
            if (!indicator.isCanceled && ok > 0) {
                notify("opencode explained $ok failed job(s)" + (if (failed > 0) ", $failed failed (see the GitLab CR log)" else "") + ". See the GitLab CR tool window.")
            }
        }
    }

    fun fixJob(job: Job) {
        background("opencode: analysing job \"${job.name}\"") { indicator ->
            val t = explainJobBlocking(job, indicator)
            ui {
                if (t.file != null) openThread(t) else each { it.showThread(t) }
                notify("${job.name}: ${t.notes[0].body.lines().drop(1).firstOrNull { it.isNotBlank() } ?: "see the GitLab CR tool window"}")
            }
        }
    }

    /** Gets the job log, asks opencode for the cause and a fix, and shows the result as a thread. Blocking. */
    fun explainJobBlocking(job: Job, indicator: ProgressIndicator?): CrThread {
        val s = session ?: throw UserException("No merge request loaded.")
        val mr = s.mr ?: throw UserException("No merge request loaded.")
        val ps = projectSettings()
        val log = cleanJobLog(s.client.jobTrace(s.project, job.id), maxOf(4, settings().pipelineMaxLogKb) * 1024)
        if (log.text.isBlank()) throw UserException("The log of job \"${job.name}\" is empty.")
        val baseBranch = ps.selfReviewBaseBranch.ifBlank { mr.targetBranch }
        val files = try {
            Git.changedFiles(s.root, Git.mergeBase(s.root, baseBranch, ps.remoteName.ifBlank { "origin" }))
        } catch (e: Exception) {
            log("Cannot list the changed files: ${e.message}")
            emptyList()
        }
        val raw = askOpencode(s.root, pipelinePrompt(job.name, job.stage, job.failureReason, log.text, log.truncated, files, baseBranch), indicator)
        val answer = validateFixAnswer(raw)
        val cause = raw.asJsonObject.str("cause")?.trim()?.ifEmpty { null } ?: answer.explanation.ifEmpty { "No cause given." }
        val edits = answer.edits.map { e -> prepareEdit(text(document(s.root, e.file)), e) }
        val body = "**Pipeline #${pipeline?.pipeline?.id ?: "?"}: job \"${job.name}\" (${job.stage}) failed.**\n\n$cause"
        return onEdt { addPipelineThread(job, body, edits.firstOrNull()?.file, edits.firstOrNull()?.startLine, FixSuggestion(answer.explanation, edits), false) }
    }

    private fun addPipelineThread(job: Job, body: String, file: String?, line: Int?, suggestion: FixSuggestion?, skipAutoFix: Boolean): CrThread {
        val id = "pipeline-${job.id}"
        val exists = file != null && session?.root?.let { File(it, file).exists() } == true
        val t = CrThread(id, ThreadSource.PIPELINE, mutableListOf(CrNote(author = "opencode CI analysis", body = body)), if (exists) file else null, if (exists) line else null)
        t.subtitle = job.name
        t.jobUrl = job.webUrl
        t.suggestion = suggestion?.takeIf { it.edits.isNotEmpty() }
        t.skipAutoFix = skipAutoFix
        pipelineThreads = pipelineThreads.filter { it.id != id } + t
        saveJobCache(t, false)
        fireChanged()
        return t
    }

    fun jobAction(job: Job, action: String) {
        val s = session ?: throw UserException("No merge request loaded.")
        if (action == "cancel" && Messages.showYesNoDialog(project, "Cancel job \"${job.name}\"?", "Cancel Job", Messages.getWarningIcon()) != Messages.YES) return
        background("GitLab: $action job ${job.name}", cancellable = false) {
            when (action) {
                "retry" -> s.client.retryJob(s.project, job.id)
                "play" -> s.client.playJob(s.project, job.id)
                else -> s.client.cancelJob(s.project, job.id)
            }
            loadPipelineBlocking(s)
        }
    }

    fun pipelineAction(action: String) {
        val s = session ?: throw UserException("No merge request loaded.")
        val p = pipeline?.pipeline ?: throw UserException("No pipeline loaded.")
        if (action == "cancel" && Messages.showYesNoDialog(project, "Cancel pipeline #${p.id}?", "Cancel Pipeline", Messages.getWarningIcon()) != Messages.YES) return
        background("GitLab: $action pipeline #${p.id}", cancellable = false) {
            if (action == "retry") s.client.retryPipeline(s.project, p.id) else s.client.cancelPipeline(s.project, p.id)
            loadPipelineBlocking(s)
        }
    }

    /** Opens the job log in a read-only editor (colors and section markers removed). */
    fun showJobLog(job: Job) {
        val s = session ?: throw UserException("No merge request loaded.")
        background("Loading the log of ${job.name}") {
            val text = jobLogText(s, job)
            ui {
                val file = LightVirtualFile("${job.stage} - ${job.name}.log", FileTypeManager.getInstance().getFileTypeByExtension("log").takeIf { it.name != "UNKNOWN" } ?: PlainTextFileType.INSTANCE, text)
                file.isWritable = false
                FileEditorManager.getInstance(project).openFile(file, true)
            }
        }
    }

    fun jobLogText(s: Session, job: Job): String {
        val raw = s.client.jobTrace(s.project, job.id)
        val dur = job.duration?.let { " in ${formatDuration(it)}" } ?: ""
        return "# ${job.stage} / ${job.name} — ${statusLabel(job.status)}$dur\n# ${job.webUrl}\n\n" + cleanJobLog(raw, 50 * 1024 * 1024).text.ifEmpty { "(empty log)" }
    }

    fun showLog() {
        val file = LightVirtualFile("GitLab CR log.txt", PlainTextFileType.INSTANCE, synchronized(logText) { logText.toString() })
        file.isWritable = false
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    fun requestShowPipeline() = each { it.showPipeline() }

    companion object {
        const val CACHE_DAYS = 14
        const val CONTEXT_LINES = 40

        fun of(project: Project): CrService = project.getService(CrService::class.java)

        fun failedJobs(info: PipelineInfo) = info.jobs.filter { it.status == "failed" && !it.allowFailure }

        fun toThread(d: Discussion): CrThread? {
            val notes = d.notes.filter { !it.system }
            if (notes.isEmpty()) return null
            val pos = notes[0].position
            val file = if (pos != null && pos.positionType == "text") pos.newPath ?: pos.oldPath else null
            val line = pos?.newLine ?: pos?.oldLine
            val resolvableNotes = notes.filter { it.resolvable }
            val resolvable = resolvableNotes.isNotEmpty()
            val resolved = resolvable && resolvableNotes.all { it.resolved }
            val t = CrThread(
                d.id,
                ThreadSource.GITLAB,
                notes.map { CrNote(it.id, it.author.id, it.author.name, it.author.avatarUrl, it.body, it.createdAt) }.toMutableList(),
                file,
                if (file != null) line ?: 1 else null,
                resolvable,
                resolved,
                d.id,
            )
            // Only lines on the new side can be moved to the local file.
            if (file != null && pos?.newLine != null && pos.headSha != null) {
                t.positionSha = pos.headSha
                t.gitlabLine = pos.newLine
            }
            return t
        }
    }
}
