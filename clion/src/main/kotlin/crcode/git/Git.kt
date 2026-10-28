package crcode.git

import java.io.File
import java.util.concurrent.TimeUnit

class GitException(message: String) : Exception(message)

/** Runs the git command line. All functions block; call them from a background thread. */
object Git {
    data class Result(val code: Int, val out: String, val err: String)

    fun exec(cwd: String, vararg args: String, timeoutSeconds: Long = 60): Result {
        val p = ProcessBuilder(listOf("git", *args)).directory(File(cwd)).start()
        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { start() }
        val t2 = Thread { err.append(p.errorStream.bufferedReader().readText()) }.apply { start() }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw GitException("git ${args.joinToString(" ")} timed out")
        }
        t1.join(); t2.join()
        return Result(p.exitValue(), out.toString(), err.toString())
    }

    fun run(cwd: String, vararg args: String): String {
        val r = exec(cwd, *args)
        if (r.code != 0) throw GitException("git ${args.joinToString(" ")} failed: ${r.err.trim().ifEmpty { "exit code ${r.code}" }}")
        return r.out
    }

    fun repoRoot(dir: String) = run(dir, "rev-parse", "--show-toplevel").trim()
    fun currentBranch(root: String) = run(root, "rev-parse", "--abbrev-ref", "HEAD").trim()
    fun gitDir(root: String) = run(root, "rev-parse", "--absolute-git-dir").trim()
    fun remoteUrl(root: String, remote: String) = run(root, "remote", "get-url", remote).trim()

    fun mergeBase(root: String, baseBranch: String, remote: String): String {
        for (ref in listOf("$remote/$baseBranch", baseBranch)) {
            val r = exec(root, "merge-base", "HEAD", ref)
            if (r.code == 0) return r.out.trim()
        }
        throw GitException("Cannot find branch '$baseBranch' (also tried '$remote/$baseBranch'). Fetch it first.")
    }

    /** Diff between the merge base and the working tree, so uncommitted changes are included. */
    fun diffSince(root: String, base: String) = run(root, "diff", "--no-color", "--unified=3", base, "--")

    fun hasCommit(root: String, sha: String) = exec(root, "cat-file", "-e", "$sha^{commit}").code == 0

    /** Fetches a branch into the remote-tracking refs only; it does not change local branches or files. */
    fun fetchBranch(root: String, remote: String, branch: String) {
        run(root, "fetch", "--quiet", "--no-tags", remote, branch)
    }

    /** Diff from a commit to the working tree for one file, with no context lines. */
    fun diffFileSince(root: String, sha: String, file: String) = run(root, "diff", "--no-color", "--unified=0", sha, "--", file)

    fun changedFiles(root: String, base: String) = run(root, "diff", "--name-only", base, "--").lines().filter { it.isNotBlank() }

    fun recentSubjects(root: String, count: Int): List<String> {
        if (count <= 0) return emptyList()
        val r = exec(root, "log", "-$count", "--format=%s")
        return if (r.code == 0) r.out.lines().filter { it.isNotBlank() } else emptyList()
    }

    /**
     * Diff for the commit message.
     * `files` limits the diff to these paths (the files checked in the commit tool window); new files are included.
     * Without `files`: the staged changes, or when nothing is staged (and `source` allows), all changes of tracked files.
     */
    fun changesForCommit(root: String, source: String, files: List<String>?): Pair<String, String> {
        if (files != null && files.isNotEmpty()) {
            val tracked = mutableListOf<String>()
            val untracked = mutableListOf<String>()
            for (f in files) {
                if (exec(root, "ls-files", "--error-unmatch", "--", f).code == 0) tracked.add(f) else untracked.add(f)
            }
            val sb = StringBuilder()
            if (tracked.isNotEmpty()) sb.append(run(root, "diff", "--no-color", "--unified=3", "HEAD", "--", *tracked.toTypedArray()))
            for (f in untracked) {
                // `git diff --no-index` exits with 1 when the files differ; that is the normal case here.
                sb.append(exec(root, "diff", "--no-color", "--no-index", "--", "/dev/null", f).out)
            }
            val stat = if (tracked.isNotEmpty()) run(root, "diff", "--stat=100", "HEAD", "--", *tracked.toTypedArray()) else ""
            return sb.toString() to (stat + untracked.joinToString("") { " $it (new file)\n" })
        }
        if (source != "all") {
            val staged = run(root, "diff", "--no-color", "--cached", "--unified=3", "--")
            if (staged.isNotBlank() || source == "staged") return staged to run(root, "diff", "--cached", "--stat=100", "--")
        }
        return run(root, "diff", "--no-color", "--unified=3", "HEAD", "--") to run(root, "diff", "--stat=100", "HEAD", "--")
    }
}
