import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { CommentManager, CrComment } from './comments';
import { CommitWriter } from './commit';
import { applyGrouped, prepareEdit, previewTexts, resolveInRoot } from './edits';
import * as git from './git';
import { Discussion, GitLabClient, GitLabUser, Job, MergeRequest } from './gitlab';
import { CrThread, FixSuggestion } from './model';
import { OpencodeRunner } from './opencode';
import { mapLine, numberLines, parseHunks, validateFixAnswer } from './parse';
import { groupStages, jobActions, shownStatus } from './ciModel';
import { PipelineInfo, PipelineStatusBar, failedJobs, isActive, statusIcon, statusText } from './pipeline';
import { PipelineView, PipelineViewData, PipelineViewMessage, toViewStage } from './pipelineView';
import { fixPrompt, pipelinePrompt, replyPrompt, selfReviewPrompt } from './prompts';
import { cleanJobLog, formatDuration } from './text';
import { ThreadTree } from './tree';

const TOKEN_KEY = 'gitlabCr.token';
export const PREVIEW_SCHEME = 'gitlabcr-preview';
export const LOG_SCHEME = 'gitlabcr-log';
const CACHE_KEY = 'gitlabCr.suggestionCache';
const PIPELINE_CACHE_KEY = 'gitlabCr.pipelineCache';
const CACHE_DAYS = 14;
const CONTEXT_LINES = 40;

interface CacheEntry {
  at: number;
  suggestion?: FixSuggestion;
  draftReply?: string;
  skipAutoFix?: boolean;
  skipAutoReply?: boolean;
}

/** What opencode found for a failed job, kept per workspace so a restart does not ask again. */
interface JobCacheEntry {
  at: number;
  body: string;
  file?: string;
  line?: number;
  suggestion?: FixSuggestion;
  skipAutoFix: boolean;
  dismissed: boolean;
}

interface Session {
  root: string;
  project: string;
  baseUrl: string;
  client: GitLabClient;
  me?: GitLabUser;
  mr?: MergeRequest;
}

export class Controller implements vscode.Disposable {
  private session?: Session;
  private refreshing?: Promise<void>;
  private refreshQueued?: Promise<void>;
  private branch?: string;
  private watchedGitDir?: string;
  private headWatcher?: vscode.FileSystemWatcher;
  private branchTimer?: NodeJS.Timeout;
  private autoRun?: vscode.CancellationTokenSource;
  private me?: { baseUrl: string; user: GitLabUser };
  private lastLoad = 0;
  private readonly pollTimer: NodeJS.Timeout;
  /** Note IDs already seen per merge request, to report new notes from the periodic check. */
  private readonly seenNotes = new Map<number, Set<number>>();
  private readonly missingShas = new Set<string>();
  /** Text shown on the right side of a fix preview, by preview URI. */
  private readonly previews = new Map<string, { thread: CrThread; text: string }>();
  private readonly previewChanged = new vscode.EventEmitter<vscode.Uri>();
  private gitlabThreads: CrThread[] = [];
  private selfThreads: CrThread[] = [];
  private pipelineThreads: CrThread[] = [];
  pipeline?: PipelineInfo;
  private lastPipelineLoad = 0;
  private pipelineRun?: vscode.CancellationTokenSource;
  private remoteWatcher?: vscode.FileSystemWatcher;
  private remoteTimer?: NodeJS.Timeout;
  readonly statusBar = new PipelineStatusBar();
  readonly pipelineView: PipelineView;
  private readonly logs = new Map<string, string>();
  private readonly logChanged = new vscode.EventEmitter<vscode.Uri>();
  readonly commitWriter: CommitWriter;
  private root?: string;
  readonly comments: CommentManager;
  readonly tree = new ThreadTree();
  readonly treeView: vscode.TreeView<unknown>;
  private readonly opencode: OpencodeRunner;

  constructor(
    private readonly ctx: vscode.ExtensionContext,
    private readonly output: vscode.OutputChannel,
  ) {
    this.opencode = new OpencodeRunner(output);
    this.commitWriter = new CommitWriter(this.opencode, output);
    this.pipelineView = new PipelineView(ctx, (m) => this.onPipelineMessage(m));
    ctx.subscriptions.push(
      vscode.workspace.registerTextDocumentContentProvider(LOG_SCHEME, {
        onDidChange: this.logChanged.event,
        provideTextDocumentContent: (uri) => this.logs.get(uri.toString()) ?? 'Loading the job log…',
      }),
    );
    this.comments = new CommentManager(() => this.root);
    this.treeView = vscode.window.createTreeView('gitlabCr.threads', { treeDataProvider: this.tree as any, showCollapseAll: true });
    this.pollTimer = setInterval(() => this.poll(), 30_000);
    ctx.subscriptions.push(
      vscode.workspace.registerTextDocumentContentProvider(PREVIEW_SCHEME, {
        onDidChange: this.previewChanged.event,
        provideTextDocumentContent: (uri) => this.previews.get(uri.toString())?.text ?? '',
      }),
    );
  }

  dispose(): void {
    this.autoRun?.cancel();
    this.pipelineRun?.cancel();
    clearInterval(this.pollTimer);
    this.headWatcher?.dispose();
    this.remoteWatcher?.dispose();
    clearTimeout(this.branchTimer);
    clearTimeout(this.remoteTimer);
    this.statusBar.dispose();
    this.pipelineView.dispose();
    this.comments.dispose();
    this.treeView.dispose();
  }

  private get threads(): CrThread[] {
    return [...this.gitlabThreads, ...this.selfThreads, ...this.pipelineThreads];
  }

  private update(changed?: CrThread): void {
    let t = changed;
    if (t) {
      // A refresh can replace the thread objects while opencode works; move the result to the new object.
      const src = t;
      const live = this.gitlabThreads.find((x) => x.id === src.id && x !== src && x.cacheKey === src.cacheKey);
      if (live) {
        live.suggestion = t.suggestion;
        live.draftReply = t.draftReply;
        live.skipAutoFix = t.skipAutoFix;
        live.skipAutoReply = t.skipAutoReply;
        t = live;
      }
      this.saveCache(t);
      this.comments.render(t);
    }
    this.tree.refresh();
  }

  // ---------- suggestion cache (kept per workspace, so a restart does not ask opencode again) ----------

  private readCache(): Record<string, CacheEntry> {
    return this.ctx.workspaceState.get<Record<string, CacheEntry>>(CACHE_KEY) ?? {};
  }

  private saveCache(t: CrThread): void {
    if (t.source === 'pipeline') {
      this.saveJobCache(t, false);
      return;
    }
    if (t.source !== 'gitlab') {
      return;
    }
    const cache = this.readCache();
    const minAge = Date.now() - CACHE_DAYS * 24 * 3600 * 1000;
    for (const [k, v] of Object.entries(cache)) {
      if (v.at < minAge) {
        delete cache[k];
      }
    }
    cache[t.cacheKey] = {
      at: Date.now(),
      suggestion: t.suggestion,
      draftReply: t.draftReply,
      skipAutoFix: t.skipAutoFix,
      skipAutoReply: t.skipAutoReply,
    };
    void this.ctx.workspaceState.update(CACHE_KEY, cache);
  }

  // ---------- setup ----------

  async setToken(): Promise<void> {
    const token = await vscode.window.showInputBox({
      title: 'GitLab personal access token',
      prompt: 'Needs the "api" scope. It is stored in the VS Code secret storage.',
      password: true,
      ignoreFocusOut: true,
    });
    if (token) {
      await this.ctx.secrets.store(TOKEN_KEY, token.trim());
      vscode.window.showInformationMessage('GitLab token saved.');
      await this.refresh();
    }
  }

  private async findRoot(): Promise<string> {
    const editorUri = vscode.window.activeTextEditor?.document.uri;
    const folder =
      (editorUri && vscode.workspace.getWorkspaceFolder(editorUri)) ?? vscode.workspace.workspaceFolders?.[0];
    if (!folder) {
      throw new Error('Open a folder that contains a git repository.');
    }
    this.root = await git.repoRoot(folder.uri.fsPath);
    return this.root;
  }

  private async connect(): Promise<Session> {
    const cfg = vscode.workspace.getConfiguration('gitlabCr');
    const root = await this.findRoot();
    const remote = cfg.get<string>('remoteName') || 'origin';
    let baseUrl = cfg.get<string>('baseUrl')?.trim() ?? '';
    let project = cfg.get<string>('projectPath')?.trim() ?? '';

    if (!baseUrl || !project) {
      const parsed = git.parseRemote(await git.remoteUrl(root, remote));
      if (!parsed) {
        throw new Error(`Cannot read the GitLab project from remote '${remote}'. Set "gitlabCr.baseUrl" and "gitlabCr.projectPath".`);
      }
      baseUrl ||= parsed.guessedBaseUrl;
      project ||= git.stripBasePath(parsed.path, baseUrl);
    }

    const token = (await this.ctx.secrets.get(TOKEN_KEY)) || process.env.GITLAB_TOKEN;
    if (!token) {
      const pick = await vscode.window.showWarningMessage('A GitLab access token is needed.', 'Set Token');
      if (pick) {
        await vscode.commands.executeCommand('gitlabCr.setToken');
      }
      throw new vscode.CancellationError();
    }
    const client = new GitLabClient(baseUrl, token, cfg.get<boolean>('strictSSL') ?? true);
    return { root, project, baseUrl, client };
  }

  // ---------- load notes ----------

  /**
   * Only one refresh runs at a time, so two refreshes never add the same threads twice.
   * A call made during a refresh runs one more refresh after it (the branch can have changed).
   */
  refresh(): Promise<void> {
    if (this.refreshing) {
      this.refreshQueued ??= this.refreshing
        .catch(() => undefined)
        .then(() => {
          this.refreshQueued = undefined;
          return this.refresh();
        });
      return this.refreshQueued;
    }
    this.refreshing = this.load().finally(() => (this.refreshing = undefined));
    return this.refreshing;
  }

  private async load(): Promise<void> {
    await vscode.window.withProgress(
      { location: { viewId: 'gitlabCr.threads' }, title: 'Loading CR notes' },
      async () => {
        const s = await this.connect();
        await this.watchBranch(s.root);
        const branch = await git.currentBranch(s.root);
        this.branch = branch;
        s.mr = await s.client.findMergeRequest(s.project, branch);
        s.me = await this.currentUser(s);
        const showResolved = vscode.workspace.getConfiguration('gitlabCr').get<boolean>('showResolved') ?? false;
        const discussions = s.mr ? await s.client.discussions(s.project, s.mr.iid) : [];

        const fresh = discussions
          .map((d) => toThread(d))
          .filter((t): t is CrThread => !!t && (showResolved || !t.resolved));
        await this.mapToLocalLines(s, fresh);
        const newNotes = s.mr ? this.countNewNotes(s.mr.iid, fresh, s.me) : 0;

        // Change only the threads that changed, so open reply boxes and drafts being edited stay as they are.
        const previous = new Map(this.gitlabThreads.map((t) => [t.id, t]));
        const cache = this.readCache();
        const added: CrThread[] = [];
        this.session = s;
        this.gitlabThreads = fresh.map((t) => {
          const old = previous.get(t.id);
          const shown = !!old?.vsThread || !old?.file; // a general note has no editor thread
          if (old && old.sameAs(t) && shown) {
            previous.delete(t.id);
            return old;
          }
          // Keep suggestions and drafts made before the refresh or before VS Code was restarted.
          const saved: Omit<CacheEntry, 'at'> | undefined = old && old.cacheKey === t.cacheKey ? old : cache[t.cacheKey];
          t.suggestion = saved?.suggestion;
          t.draftReply = saved?.draftReply;
          t.skipAutoFix = saved?.skipAutoFix ?? false;
          t.skipAutoReply = saved?.skipAutoReply ?? false;
          added.push(t);
          return t;
        });
        for (const gone of previous.values()) {
          this.comments.remove(gone);
        }
        if (!s.mr) {
          this.treeView.message = `No open merge request for branch '${branch}' in ${s.project}.`;
        } else {
          const open = this.gitlabThreads.filter((t) => !t.resolved).length;
          this.treeView.message = `!${s.mr.iid} ${s.mr.title} (${open} open note${open === 1 ? '' : 's'})`;
        }
        this.comments.show(added);
        this.tree.setThreads(this.threads);
        this.lastLoad = Date.now();
        await this.loadPipeline(s);
        if (newNotes) {
          void vscode.window
            .showInformationMessage(`${newNotes} new review note(s) on !${s.mr!.iid}.`, 'Show')
            .then((c) => c && vscode.commands.executeCommand('gitlabCr.threads.focus'));
        }
      },
    );
    this.startAutoSuggest();
  }

  /**
   * GitLab gives line numbers of the pushed commit. Moves them to the lines of the local files
   * (with `git diff <commit>` for each file) and marks notes whose line was changed as outdated.
   */
  private async mapToLocalLines(s: Session, threads: CrThread[]): Promise<void> {
    const remote = vscode.workspace.getConfiguration('gitlabCr').get<string>('remoteName') || 'origin';
    const shas = new Map<string, boolean>();
    let fetched = false;
    const hasSha = async (sha: string) => {
      if (!shas.has(sha)) {
        let ok = await git.hasCommit(s.root, sha);
        if (!ok && s.mr && !fetched && !this.missingShas.has(sha)) {
          // Someone else may have pushed to the branch. Fetch at most once per load.
          fetched = true;
          await git.fetchBranch(s.root, remote, s.mr.source_branch).catch((err) =>
            this.output.appendLine(`Cannot fetch ${s.mr!.source_branch}: ${(err as Error).message}`),
          );
          ok = await git.hasCommit(s.root, sha);
        }
        if (!ok) {
          this.missingShas.add(sha); // for example a commit removed by a force push
        }
        shas.set(sha, ok);
      }
      return shas.get(sha)!;
    };
    const diffs = new Map<string, Promise<string>>();
    for (const t of threads) {
      if (!t.file || !t.gitlabLine || !t.positionSha) {
        continue;
      }
      if (!(await hasSha(t.positionSha))) {
        this.output.appendLine(`Commit ${t.positionSha.slice(0, 8)} is not available locally; ${t.location} is not moved.`);
        continue;
      }
      const key = `${t.positionSha}:${t.file}`;
      if (!diffs.has(key)) {
        diffs.set(key, git.diffFileSince(s.root, t.positionSha, t.file).catch(() => ''));
      }
      const mapped = mapLine(parseHunks(await diffs.get(key)!), t.gitlabLine);
      t.line = mapped.line;
      t.outdated = mapped.outdated;
    }
  }

  /** Counts notes by other people that were not seen in the last load of the same merge request. */
  private countNewNotes(iid: number, threads: CrThread[], me: GitLabUser | undefined): number {
    const ids = threads.flatMap((t) => t.notes.filter((n) => n.authorId !== me?.id).map((n) => n.id ?? -1));
    const seen = this.seenNotes.get(iid);
    this.seenNotes.set(iid, new Set([...(seen ?? []), ...ids]));
    return seen ? ids.filter((id) => !seen.has(id)).length : 0;
  }

  /** Periodic check for new notes while the VS Code window has focus. */
  private poll(): void {
    const minutes = vscode.workspace.getConfiguration('gitlabCr.auto').get<number>('pollMinutes', 5);
    if (!this.session || this.refreshing || !vscode.window.state.focused) {
      return;
    }
    // A running pipeline is checked more often than the notes, so the status bar follows it.
    const seconds = vscode.workspace.getConfiguration('gitlabCr.pipeline').get<number>('pollSeconds', 60);
    if (seconds > 0 && this.pipeline && isActive(this.pipeline.pipeline.status) && Date.now() - this.lastPipelineLoad >= seconds * 1000) {
      this.lastPipelineLoad = Date.now();
      void this.loadPipeline(this.session);
    }
    if (!minutes || minutes <= 0 || Date.now() - this.lastLoad < minutes * 60_000) {
      return;
    }
    this.lastLoad = Date.now(); // do not try again at once when the load fails
    this.refresh().catch((err) => {
      if (!(err instanceof vscode.CancellationError)) {
        this.output.appendLine(`Periodic check failed: ${(err as Error).message}`);
      }
    });
  }

  private async currentUser(s: Session): Promise<GitLabUser | undefined> {
    if (this.me?.baseUrl !== s.baseUrl) {
      try {
        this.me = { baseUrl: s.baseUrl, user: await s.client.currentUser() };
      } catch (err) {
        this.output.appendLine(`Cannot read the current GitLab user: ${(err as Error).message}`);
        return undefined;
      }
    }
    return this.me.user;
  }

  // ---------- automatic work ----------

  /** Refreshes when the checked-out branch changes (checkout, switch, or a new branch). */
  private async watchBranch(root: string): Promise<void> {
    const dir = await git.gitDir(root);
    if (dir === this.watchedGitDir) {
      return;
    }
    this.headWatcher?.dispose();
    this.watchedGitDir = dir;
    const watcher = vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(vscode.Uri.file(dir), 'HEAD'));
    const onHead = () => {
      clearTimeout(this.branchTimer);
      this.branchTimer = setTimeout(() => void this.onHeadChanged(root), 700);
    };
    watcher.onDidChange(onHead);
    watcher.onDidCreate(onHead);
    this.headWatcher = watcher;

    // A push updates the remote-tracking ref; a new pipeline usually starts then.
    this.remoteWatcher?.dispose();
    const remotes = vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(vscode.Uri.file(dir), '{refs/remotes/**,packed-refs}'));
    const onRemote = () => {
      clearTimeout(this.remoteTimer);
      this.remoteTimer = setTimeout(() => {
        if (this.session?.mr && !this.refreshing) {
          void this.loadPipeline(this.session);
        }
      }, 3000);
    };
    remotes.onDidChange(onRemote);
    remotes.onDidCreate(onRemote);
    this.remoteWatcher = remotes;
  }

  private async onHeadChanged(root: string): Promise<void> {
    if (!vscode.workspace.getConfiguration('gitlabCr.auto').get<boolean>('refreshOnBranchChange', true)) {
      return;
    }
    try {
      const branch = await git.currentBranch(root);
      if (branch === this.branch || branch === 'HEAD') {
        return; // same branch (for example a commit) or detached HEAD during a rebase
      }
      this.output.appendLine(`Branch changed: ${this.branch} -> ${branch}. Loading its merge request.`);
      this.branch = branch;
      await this.refresh();
    } catch (err) {
      if (!(err instanceof vscode.CancellationError)) {
        this.output.appendLine(`Refresh after branch change failed: ${(err as Error).message}`);
      }
    }
  }

  private isMine(t: CrThread): boolean {
    const me = this.session?.me;
    const last = t.notes[t.notes.length - 1];
    return !!me && last?.authorId === me.id;
  }

  /**
   * Asks opencode in the background for fixes and reply drafts for the open notes.
   * Skips notes that already have them, notes the user handled, and notes where the user wrote the last reply.
   */
  private startAutoSuggest(): void {
    this.autoRun?.cancel();
    const cfg = vscode.workspace.getConfiguration('gitlabCr.auto');
    const doFix = cfg.get<boolean>('suggestFixes', true);
    const doReply = cfg.get<boolean>('suggestReplies', true);
    const open = this.gitlabThreads.filter((t) => !t.resolved && !this.isMine(t));
    const jobs: { t: CrThread; kind: 'fix' | 'reply' }[] = [];
    for (const t of open) {
      // An outdated note is often already fixed; its fix is made only on request.
      if (doFix && t.file && t.line && !t.outdated && !t.suggestion && !t.skipAutoFix) {
        jobs.push({ t, kind: 'fix' });
      }
      if (doReply && t.draftReply === undefined && !t.skipAutoReply) {
        jobs.push({ t, kind: 'reply' });
      }
    }
    if (!jobs.length) {
      return;
    }
    const cts = new vscode.CancellationTokenSource();
    this.autoRun = cts;
    const mr = this.session?.mr;
    void vscode.window.withProgress(
      { location: vscode.ProgressLocation.Window, title: 'opencode: preparing CR suggestions' },
      async (progress) => {
        let fixes = 0;
        let replies = 0;
        let failed = 0;
        let done = 0;
        const concurrency = vscode.workspace.getConfiguration('gitlabCr.fixAll').get<number>('concurrency') ?? 2;
        await runPool(jobs, concurrency, cts.token, async ({ t, kind }) => {
          try {
            if (kind === 'fix') {
              await this.generateFix(t, cts.token);
              fixes++;
            } else {
              await this.generateReply(t, cts.token);
              replies++;
            }
            this.update(t);
          } catch (err) {
            if (!(err instanceof vscode.CancellationError)) {
              failed++;
              this.output.appendLine(`Automatic ${kind} for ${t.location} failed: ${(err as Error).message}`);
            }
          }
          progress.report({ message: `${++done}/${jobs.length}` });
        });
        if (cts.token.isCancellationRequested || (!fixes && !replies && !failed)) {
          return;
        }
        // Not awaited: the status bar progress must stop now, not when the user closes the message.
        void vscode.window
          .showInformationMessage(
            `opencode prepared ${fixes} fix(es) and ${replies} reply draft(s)` +
              (mr ? ` for !${mr.iid}` : '') +
              (failed ? `. ${failed} failed (see the "GitLab CR" output).` : '.'),
            'Show',
          )
          .then((choice) => choice && vscode.commands.executeCommand('gitlabCr.threads.focus'));
      },
    );
  }

  async openInBrowser(): Promise<void> {
    if (!this.session?.mr) {
      await this.refresh();
    }
    if (this.session?.mr) {
      vscode.env.openExternal(vscode.Uri.parse(this.session.mr.web_url));
    } else {
      vscode.window.showInformationMessage('No open merge request for the current branch.');
    }
  }

  // ---------- thread lookup ----------

  /** Finds the CR thread for any argument VS Code can pass to a command. */
  async threadFrom(arg: unknown): Promise<CrThread | undefined> {
    if (arg instanceof CrComment) {
      return arg.crThread;
    }
    const fromTree = ThreadTree.threadOf(arg);
    if (fromTree) {
      return fromTree;
    }
    const a = arg as { thread?: vscode.CommentThread; uri?: vscode.Uri; comments?: unknown };
    if (a?.thread) {
      return this.comments.lookup(a.thread);
    }
    if (a?.uri && a?.comments) {
      return this.comments.lookup(arg as vscode.CommentThread);
    }

    // Command palette: use the thread at the cursor, or ask.
    const editor = vscode.window.activeTextEditor;
    if (editor && this.root) {
      const rel = path.relative(this.root, editor.document.uri.fsPath).split(path.sep).join('/');
      const line = editor.selection.active.line + 1;
      const here = this.threads.find((t) => t.file === rel && t.line === line);
      if (here) {
        return here;
      }
    }
    if (!this.threads.length) {
      vscode.window.showInformationMessage('No CR notes loaded. Run "GitLab CR: Refresh CR Notes" first.');
      return undefined;
    }
    const pick = await vscode.window.showQuickPick(
      this.threads.map((t) => ({ label: t.title, description: t.location, thread: t })),
      { placeHolder: 'Select a review note' },
    );
    return pick?.thread;
  }

  async openThread(t: CrThread): Promise<void> {
    if (!t.file || !this.root) {
      return;
    }
    const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(path.join(this.root, t.file)));
    const editor = await vscode.window.showTextDocument(doc, { preview: true });
    const line = Math.min(Math.max(0, (t.line ?? 1) - 1), doc.lineCount - 1);
    editor.selection = new vscode.Selection(line, 0, line, 0);
    editor.revealRange(new vscode.Range(line, 0, line, 0), vscode.TextEditorRevealType.InCenter);
    if (t.vsThread) {
      t.vsThread.collapsibleState = vscode.CommentThreadCollapsibleState.Expanded;
    }
  }

  // ---------- fixes ----------

  private async generateFix(t: CrThread, token?: vscode.CancellationToken): Promise<void> {
    const root = this.root ?? (await this.findRoot());
    if (!t.file || !t.line) {
      throw new Error('This note is not attached to a line of code.');
    }
    const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(resolveInRoot(root, t.file)));
    const lines = doc.getText().split(/\r?\n/);
    const start = Math.max(1, t.line - CONTEXT_LINES);
    const end = Math.min(lines.length, t.line + CONTEXT_LINES);
    const prompt = fixPrompt(t, numberLines(lines.slice(start - 1, end), start), start, end);

    const answer = validateFixAnswer(await this.opencode.askJson(root, prompt, token));
    const edits = [];
    for (const e of answer.edits) {
      const target = await vscode.workspace.openTextDocument(vscode.Uri.file(resolveInRoot(root, e.file)));
      edits.push(prepareEdit(target, e));
    }
    t.suggestion = { explanation: answer.explanation, edits };
  }

  async suggestFix(arg: unknown): Promise<void> {
    const t = await this.threadFrom(arg);
    if (!t) {
      return;
    }
    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: `opencode: fixing "${t.title.slice(0, 60)}"`, cancellable: true },
      (_p, token) => this.generateFix(t, token),
    );
    this.update(t);
    await this.openThread(t);
    if (t.suggestion && !t.suggestion.edits.length) {
      vscode.window.showInformationMessage(`opencode suggests no code change: ${t.suggestion.explanation}`);
    }
  }

  async applyFix(arg: unknown): Promise<void> {
    const t = await this.threadFrom(arg);
    if (!t) {
      return;
    }
    if (!t.suggestion) {
      await this.suggestFix(t);
      if (!t.suggestion) {
        return;
      }
    }
    if (!t.suggestion.edits.length) {
      vscode.window.showInformationMessage('This suggestion has no code change to apply.');
      return;
    }
    const result = await applyGrouped(this.root!, [{ owner: t, edits: t.suggestion.edits }]);
    if (result.failed.length) {
      throw new Error(result.failed[0].reason);
    }
    t.suggestion = undefined;
    t.skipAutoFix = true;
    this.update(t);
    vscode.window.setStatusBarMessage('$(check) Fix applied. Review and save the file.', 5000);
  }

  /** Shows the suggested fix in the diff editor: current file on the left, file after the fix on the right. */
  async previewFix(arg: unknown): Promise<void> {
    const t = await this.threadFrom(arg);
    if (!t) {
      return;
    }
    if (!t.suggestion) {
      await this.suggestFix(t);
    }
    if (!t.suggestion?.edits.length) {
      return;
    }
    const root = this.root!;
    for (const [file, text] of await previewTexts(root, t.suggestion.edits)) {
      const right = vscode.Uri.from({ scheme: PREVIEW_SCHEME, path: '/' + file, query: t.id });
      this.previews.set(right.toString(), { thread: t, text });
      this.previewChanged.fire(right);
      await vscode.commands.executeCommand(
        'vscode.diff',
        vscode.Uri.file(path.join(root, file)),
        right,
        `${path.basename(file)} ↔ opencode fix`,
        { preview: true },
      );
    }
  }

  /** "Apply Fix" in the title of a preview diff editor. */
  async applyFromPreview(uri?: vscode.Uri): Promise<void> {
    const tab = vscode.window.tabGroups.activeTabGroup.activeTab;
    const input = tab?.input;
    const right = uri?.scheme === PREVIEW_SCHEME ? uri : input instanceof vscode.TabInputTextDiff ? input.modified : undefined;
    const entry = right && this.previews.get(right.toString());
    if (!entry) {
      throw new Error('This editor is not an opencode fix preview.');
    }
    await this.applyFix(entry.thread);
    if (!entry.thread.suggestion) {
      // Applied: close the previews of this fix.
      for (const [key, p] of [...this.previews]) {
        if (p.thread === entry.thread) {
          this.previews.delete(key);
        }
      }
      const tabs = vscode.window.tabGroups.all
        .flatMap((g) => g.tabs)
        .filter((x) => x.input instanceof vscode.TabInputTextDiff && x.input.modified.scheme === PREVIEW_SCHEME && x.input.modified.query === entry.thread.id);
      await vscode.window.tabGroups.close(tabs);
    }
  }

  /** "Fix CR for all page": makes and applies fixes for every open note of the merge request. */
  async fixAll(): Promise<void> {
    let pool = this.gitlabThreads;
    if (this.selfThreads.length || this.pipelineThreads.length) {
      const options = [{ label: 'GitLab review notes', value: this.gitlabThreads }];
      if (this.selfThreads.length) {
        options.push({ label: 'Self review findings', value: this.selfThreads });
      }
      if (this.pipelineThreads.length) {
        options.push({ label: 'CI failures', value: this.pipelineThreads });
      }
      options.push({ label: 'All', value: this.threads });
      const pick = await vscode.window.showQuickPick(options, { placeHolder: 'Which notes do you want to fix?' });
      if (!pick) {
        return;
      }
      pool = pick.value;
    }
    // Skip notes whose fix was already applied or discarded: an applied insert must not go in twice.
    const targets = pool.filter((t) => t.file && t.line && !t.resolved && !t.skipAutoFix);
    if (!targets.length) {
      vscode.window.showInformationMessage('There are no open notes on code lines to fix.');
      return;
    }

    const errors: string[] = [];
    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'opencode: fixing all CR notes', cancellable: true },
      async (progress, token) => {
        const todo = targets.filter((t) => !t.suggestion);
        const concurrency = vscode.workspace.getConfiguration('gitlabCr.fixAll').get<number>('concurrency') ?? 2;
        let done = 0;
        progress.report({ message: `0/${todo.length}` });
        await runPool(todo, concurrency, token, async (t) => {
          try {
            await this.generateFix(t, token);
            this.update(t);
          } catch (err) {
            if (!(err instanceof vscode.CancellationError)) {
              errors.push(`${t.location}: ${(err as Error).message}`);
            }
          }
          done++;
          progress.report({ message: `${done}/${todo.length}`, increment: 100 / todo.length });
        });
        if (token.isCancellationRequested) {
          throw new vscode.CancellationError();
        }
      },
    );

    const ready = targets.filter((t) => t.suggestion?.edits.length);
    const noChange = targets.filter((t) => t.suggestion && !t.suggestion.edits.length).length;
    for (const e of errors) {
      this.output.appendLine('Fix failed: ' + e);
    }
    if (!ready.length) {
      vscode.window.showWarningMessage(
        `No fixes to apply. ${noChange} note(s) need no code change, ${errors.length} failed (see the "GitLab CR" output).`,
      );
      return;
    }
    const confirm = vscode.workspace.getConfiguration('gitlabCr.fixAll').get<boolean>('confirm') ?? true;
    const choice = !confirm
      ? 'Apply All'
      : await vscode.window.showInformationMessage(
          `opencode prepared fixes for ${ready.length} of ${targets.length} notes` +
            (noChange ? `, ${noChange} need no code change` : '') +
            (errors.length ? `, ${errors.length} failed` : '') +
            '. Apply all fixes now?',
          { modal: true },
          'Apply All',
          'Review One by One',
        );
    if (choice === 'Review One by One') {
      await this.openThread(ready[0]);
      return;
    }
    if (choice !== 'Apply All') {
      return;
    }
    const result = await applyGrouped(
      this.root!,
      ready.map((t) => ({ owner: t, edits: t.suggestion!.edits })),
    );
    for (const t of result.applied) {
      t.suggestion = undefined;
      t.skipAutoFix = true;
      this.update(t);
    }
    for (const f of result.failed) {
      this.output.appendLine(`Not applied ${f.owner.location}: ${f.reason}`);
    }
    const msg = `Applied ${result.applied.length} fix(es). Review and save the files.`;
    if (result.failed.length) {
      vscode.window.showWarningMessage(`${msg} ${result.failed.length} not applied (see the "GitLab CR" output).`);
      this.output.show(true);
    } else {
      vscode.window.showInformationMessage(msg);
    }
  }

  // ---------- replies ----------

  private requireGitLab(t: CrThread): { s: Session; mr: MergeRequest; discussionId: string } {
    const s = this.session;
    if (t.source !== 'gitlab' || !t.discussionId || !s?.mr) {
      throw new Error('This note is not a GitLab discussion.');
    }
    return { s, mr: s.mr, discussionId: t.discussionId };
  }

  private async postReply(t: CrThread, body: string): Promise<void> {
    const { s, mr, discussionId } = this.requireGitLab(t);
    const note = await s.client.addNote(s.project, mr.iid, discussionId, body);
    t.notes.push({
      id: note.id,
      authorId: note.author.id,
      author: note.author.name,
      avatarUrl: note.author.avatar_url,
      body: note.body,
      createdAt: note.created_at,
    });
    this.update(t);
  }

  /** Called with the text in the comment reply box, or from the tree / command palette. */
  async reply(arg: unknown): Promise<void> {
    const t = await this.threadFrom(arg);
    if (!t) {
      return;
    }
    let text: string | undefined = (arg as vscode.CommentReply)?.text;
    if (text === undefined) {
      text = await this.editText(t.draftReply ?? '', `Reply to: ${t.title}`);
    }
    if (!text?.trim()) {
      return;
    }
    await this.postReply(t, text.trim());
  }

  private async generateReply(t: CrThread, token?: vscode.CancellationToken): Promise<void> {
    const root = this.root ?? (await this.findRoot());
    let context: string | undefined;
    if (t.file && t.line) {
      try {
        const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(resolveInRoot(root, t.file)));
        const lines = doc.getText().split(/\r?\n/);
        const start = Math.max(1, t.line - 15);
        context = numberLines(lines.slice(start - 1, Math.min(lines.length, t.line + 15)), start);
      } catch {
        // the file may not exist locally; opencode can still answer from the conversation
      }
    }
    const answer = await this.opencode.askJson<{ reply?: string }>(root, replyPrompt(t, context), token);
    if (!answer.reply?.trim()) {
      throw new Error('opencode returned an empty reply.');
    }
    t.draftReply = answer.reply.trim();
  }

  async suggestReply(arg: unknown): Promise<void> {
    const t = await this.threadFrom(arg);
    if (!t) {
      return;
    }
    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'opencode: writing a reply', cancellable: true },
      (_p, token) => this.generateReply(t, token),
    );
    this.update(t);

    if (t.vsThread) {
      await this.openThread(t);
      return;
    }
    // General notes have no editor thread: let the user edit the draft in a document.
    const text = await this.editText(t.draftReply ?? '', `Suggested reply to: ${t.title}`);
    if (text?.trim()) {
      t.draftReply = undefined;
      await this.postReply(t, text.trim());
    }
  }

  /** Opens an untitled markdown document with the text and returns its content when the user clicks Send. */
  private async editText(initial: string, title: string): Promise<string | undefined> {
    const doc = await vscode.workspace.openTextDocument({ language: 'markdown', content: initial });
    await vscode.window.showTextDocument(doc, { preview: false, viewColumn: vscode.ViewColumn.Beside });
    const choice = await vscode.window.showInformationMessage(`${title}\n\nEdit the reply in the editor, then click Send.`, 'Send', 'Cancel');
    const text = doc.getText();
    if (choice !== 'Send') {
      return undefined;
    }
    return text;
  }

  async sendDraft(c: CrComment): Promise<void> {
    const t = c.crThread;
    const body = t.draftReply?.trim();
    if (!body) {
      return;
    }
    t.draftReply = undefined;
    await this.postReply(t, body);
  }

  editDraft(c: CrComment): void {
    const vs = c.crThread.vsThread;
    if (!vs) {
      return;
    }
    vs.comments = vs.comments.map((x) => {
      if (x instanceof CrComment && x.id === c.id) {
        x.mode = vscode.CommentMode.Editing;
        x.body = x.savedBody;
      }
      return x;
    });
  }

  saveDraft(c: CrComment): void {
    const text = typeof c.body === 'string' ? c.body : c.body.value;
    c.crThread.draftReply = text;
    this.update(c.crThread);
  }

  cancelDraftEdit(c: CrComment): void {
    this.update(c.crThread);
  }

  discard(c: CrComment): void {
    const t = c.crThread;
    if (c.kind === 'draft') {
      t.draftReply = undefined;
      t.skipAutoReply = true;
    } else if (c.kind === 'suggestion') {
      t.suggestion = undefined;
      t.skipAutoFix = true;
    }
    this.update(t);
  }

  async toggleResolved(arg: unknown): Promise<void> {
    const t = await this.threadFrom(arg);
    if (!t) {
      return;
    }
    const { s, mr, discussionId } = this.requireGitLab(t);
    await s.client.setResolved(s.project, mr.iid, discussionId, !t.resolved);
    t.resolved = !t.resolved;
    this.update(t);
  }

  // ---------- self review ----------

  async selfReview(): Promise<void> {
    const root = await this.findRoot();
    const cfg = vscode.workspace.getConfiguration('gitlabCr');
    const remote = cfg.get<string>('remoteName') || 'origin';
    if (!this.session) {
      // The merge request gives the target branch; continue without it when GitLab is not reachable.
      await this.refresh().catch((err) => this.output.appendLine(`Cannot load merge request: ${(err as Error).message}`));
    }
    const baseBranch = cfg.get<string>('selfReview.baseBranch') || this.session?.mr?.target_branch || 'main';
    const base = await git.mergeBase(root, baseBranch, remote);
    let diff = await git.diffSince(root, base);
    if (!diff.trim()) {
      vscode.window.showInformationMessage(`There are no changes compared to '${baseBranch}'.`);
      return;
    }
    const maxBytes = (cfg.get<number>('selfReview.maxDiffKb') ?? 200) * 1024;
    const truncated = Buffer.byteLength(diff) > maxBytes;
    if (truncated) {
      diff = Buffer.from(diff).subarray(0, maxBytes).toString('utf8');
    }

    const answer = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: `opencode: reviewing changes against ${baseBranch}`, cancellable: true },
      (_p, token) => this.opencode.askJson<{ findings?: any[] }>(root, selfReviewPrompt(baseBranch, diff, truncated), token),
    );

    this.comments.clear('self');
    let n = 0;
    this.selfThreads = (answer.findings ?? [])
      .filter((f) => typeof f?.file === 'string' && typeof f?.comment === 'string')
      .map((f) => {
        const file = String(f.file).replace(/^[ab]\//, '').replace(/^\.\//, '');
        let abs: string;
        try {
          abs = resolveInRoot(root, file);
        } catch {
          return undefined;
        }
        const exists = fs.existsSync(abs);
        const line = Number.isInteger(Number(f.line)) && Number(f.line) > 0 ? Number(f.line) : 1;
        const severity = typeof f.severity === 'string' ? f.severity.toLowerCase() : undefined;
        return new CrThread(
          `self-${Date.now()}-${n++}`,
          'self',
          [{ author: 'opencode self review', body: `${severity ? `**[${severity}]** ` : ''}${f.comment}` }],
          exists ? file : undefined,
          exists ? line : undefined,
          false,
          false,
          undefined,
          severity,
        );
      })
      .filter((t): t is CrThread => !!t);

    this.comments.show(this.selfThreads);
    this.tree.setThreads(this.threads);
    if (!this.selfThreads.length) {
      vscode.window.showInformationMessage('Self review: opencode found no issues.');
    } else {
      vscode.window.showInformationMessage(`Self review: ${this.selfThreads.length} finding(s). See the GitLab CR view.`);
      vscode.commands.executeCommand('gitlabCr.threads.focus');
    }
  }

  clearSelfReview(): void {
    this.comments.clear('self');
    this.selfThreads = [];
    this.tree.setThreads(this.threads);
  }

  async dismissFinding(arg: unknown): Promise<void> {
    const t = await this.threadFrom(arg);
    if (!t || (t.source !== 'self' && t.source !== 'pipeline')) {
      return;
    }
    this.comments.remove(t);
    this.selfThreads = this.selfThreads.filter((x) => x !== t);
    this.pipelineThreads = this.pipelineThreads.filter((x) => x !== t);
    if (t.source === 'pipeline') {
      this.saveJobCache(t, true);
      this.pipelineView.update(this.pipelineData());
    }
    this.tree.setThreads(this.threads);
  }

  // ---------- commit message ----------

  async writeCommitMessage(): Promise<string> {
    const root = this.root ?? (await this.findRoot());
    return this.commitWriter.write(root, this.session?.mr?.iid);
  }

  // ---------- pipeline ----------

  private readJobCache(): Record<string, JobCacheEntry> {
    return this.ctx.workspaceState.get<Record<string, JobCacheEntry>>(PIPELINE_CACHE_KEY) ?? {};
  }

  private saveJobCache(t: CrThread, dismissed: boolean): void {
    const cache = this.readJobCache();
    const minAge = Date.now() - CACHE_DAYS * 24 * 3600 * 1000;
    for (const [k, v] of Object.entries(cache)) {
      if (v.at < minAge) {
        delete cache[k];
      }
    }
    cache[t.id] = {
      at: Date.now(),
      body: t.notes[0]?.body ?? '',
      file: t.file,
      line: t.line,
      suggestion: t.suggestion,
      skipAutoFix: t.skipAutoFix,
      dismissed,
    };
    void this.ctx.workspaceState.update(PIPELINE_CACHE_KEY, cache);
  }

  /** Loads the latest pipeline of the merge request. Errors go to the log only; the notes are still useful without it. */
  private async loadPipeline(s: Session): Promise<void> {
    this.lastPipelineLoad = Date.now();
    const previous = this.pipeline;
    try {
      if (!s.mr) {
        this.setPipeline(undefined);
        return;
      }
      const pipelines = await s.client.mergeRequestPipelines(s.project, s.mr.iid);
      const latest = pipelines[0];
      if (!latest) {
        this.setPipeline(undefined);
        return;
      }
      const [jobs, details] = await Promise.all([
        s.client.pipelineJobs(s.project, latest.id),
        s.client.pipeline(s.project, latest.id).catch(() => latest),
      ]);
      this.setPipeline({ pipeline: { ...latest, ...details }, jobs }, s.mr.iid);
    } catch (err) {
      this.output.appendLine(`Cannot load the pipeline: ${(err as Error).message}`);
      return;
    }
    const info = this.pipeline;
    if (!info) {
      return;
    }
    if (previous?.pipeline.id !== info.pipeline.id) {
      // A new pipeline: the findings of the old one are not valid any more.
      this.clearPipelineFindings();
    }
    const changed = previous?.pipeline.id !== info.pipeline.id || previous.pipeline.status !== info.pipeline.status;
    if (!changed || isActive(info.pipeline.status)) {
      return;
    }
    const cfg = vscode.workspace.getConfiguration('gitlabCr.pipeline');
    const failed = failedJobs(info);
    if (info.pipeline.status === 'failed' && failed.length) {
      if (cfg.get<boolean>('notify', true) && previous) {
        void vscode.window
          .showErrorMessage(`Pipeline #${info.pipeline.id} failed: ${failed.map((j) => j.name).join(', ')}.`, 'Explain and Fix', 'Open')
          .then((c) => {
            if (c === 'Explain and Fix') {
              void vscode.commands.executeCommand('gitlabCr.fixPipeline');
            } else if (c === 'Open') {
              void vscode.env.openExternal(vscode.Uri.parse(info.pipeline.web_url));
            }
          });
      }
      if (cfg.get<boolean>('autoExplain', true)) {
        this.startAutoExplain(failed);
      }
    } else if (info.pipeline.status === 'success' && previous && cfg.get<boolean>('notify', true)) {
      vscode.window.setStatusBarMessage(`$(check) Pipeline #${info.pipeline.id} passed.`, 8000);
    }
  }

  private setPipeline(info: PipelineInfo | undefined, mrIid?: number): void {
    this.pipeline = info;
    this.statusBar.update(info, mrIid);
    this.tree.setPipeline(info);
    if (!info) {
      this.clearPipelineFindings();
    }
    this.pipelineView.update(this.pipelineData());
  }

  /** Data for the pipeline graph panel. */
  pipelineData(): PipelineViewData | undefined {
    const info = this.pipeline;
    if (!info) {
      return undefined;
    }
    const p = info.pipeline;
    const stages = groupStages(info.jobs).map((st) =>
      toViewStage(st, (jobId) => ({
        actions: jobActions(info.jobs.find((j) => j.id === jobId)!),
        hasFinding: this.pipelineThreads.some((t) => t.id === `pipeline-${jobId}`),
      })),
    );
    return {
      pipeline: {
        id: p.id,
        status: p.status,
        ref: p.ref,
        sha: p.sha,
        webUrl: p.web_url,
        duration: formatDuration(p.duration),
        createdAt: p.created_at,
        user: p.user?.name,
        source: p.source,
      },
      mrIid: this.session?.mr?.iid,
      mrTitle: this.session?.mr?.title,
      stages,
      counts: {
        total: info.jobs.length,
        failed: info.jobs.filter((j) => shownStatus(j) === 'failed').length,
        running: info.jobs.filter((j) => j.status === 'running').length,
        success: info.jobs.filter((j) => j.status === 'success').length,
      },
      updatedAt: new Date().toLocaleTimeString(),
    };
  }

  async openPipelineView(): Promise<void> {
    if (!this.pipeline && this.session) {
      await this.loadPipeline(this.session);
    } else if (!this.session) {
      await this.refresh().catch((err) => this.output.appendLine(`Cannot load: ${(err as Error).message}`));
    }
    this.pipelineView.show(this.pipelineData());
  }

  private jobById(id: number): Job {
    const job = this.pipeline?.jobs.find((j) => j.id === id);
    if (!job) {
      throw new Error(`Job ${id} is not in the current pipeline. Refresh the pipeline.`);
    }
    return job;
  }

  /** Buttons of the pipeline graph. */
  async onPipelineMessage(m: PipelineViewMessage): Promise<void> {
    try {
      switch (m.type) {
        case 'openUrl':
          await vscode.env.openExternal(vscode.Uri.parse(m.url));
          break;
        case 'refresh':
          await this.refreshPipeline();
          break;
        case 'log':
          await this.showJobLog(this.jobById(m.jobId));
          break;
        case 'fix':
          await this.fixPipeline(this.jobById(m.jobId));
          break;
        case 'finding': {
          const t = this.pipelineThreads.find((x) => x.id === `pipeline-${m.jobId}`);
          if (t?.file) {
            await this.openThread(t);
          } else {
            await vscode.commands.executeCommand('gitlabCr.threads.focus');
          }
          break;
        }
        case 'retry':
        case 'play':
        case 'cancel':
          await this.jobAction(this.jobById(m.jobId), m.type);
          break;
        case 'retryPipeline':
        case 'cancelPipeline':
          await this.pipelineAction(m.type === 'retryPipeline' ? 'retry' : 'cancel');
          break;
        case 'explainFailed':
          if (this.pipeline) {
            this.startAutoExplain(this.pipeline.jobs.filter((j) => j.status === 'failed'), true);
          }
          break;
      }
    } catch (err) {
      if (!(err instanceof vscode.CancellationError)) {
        const msg = (err as Error).message;
        this.output.appendLine(`[pipeline graph ${m.type}] ${msg}`);
        void vscode.window.showErrorMessage(msg, 'Show Log').then((pick) => pick && this.output.show());
      }
    }
  }

  /** Retry, play or cancel one job on GitLab, then reload the pipeline. */
  async jobAction(arg: unknown, action: 'retry' | 'play' | 'cancel'): Promise<void> {
    const s = this.session;
    const job = ThreadTree.jobOf(arg) ?? ((arg as Job)?.web_url ? (arg as Job) : undefined);
    if (!s || !job) {
      throw new Error('No pipeline job selected.');
    }
    if (action === 'cancel') {
      const ok = await vscode.window.showWarningMessage(`Cancel job "${job.name}"?`, { modal: true }, 'Cancel Job');
      if (ok !== 'Cancel Job') {
        return;
      }
    }
    await (action === 'retry' ? s.client.retryJob(s.project, job.id) : action === 'play' ? s.client.playJob(s.project, job.id) : s.client.cancelJob(s.project, job.id));
    vscode.window.setStatusBarMessage(`$(beaker) Job "${job.name}": ${action === 'retry' ? 'retried' : action === 'play' ? 'started' : 'canceled'}.`, 6000);
    await this.loadPipeline(s);
  }

  async pipelineAction(action: 'retry' | 'cancel'): Promise<void> {
    const s = this.session;
    const p = this.pipeline?.pipeline;
    if (!s || !p) {
      throw new Error('No pipeline loaded.');
    }
    if (action === 'cancel') {
      const ok = await vscode.window.showWarningMessage(`Cancel pipeline #${p.id}?`, { modal: true }, 'Cancel Pipeline');
      if (ok !== 'Cancel Pipeline') {
        return;
      }
    }
    await (action === 'retry' ? s.client.retryPipeline(s.project, p.id) : s.client.cancelPipeline(s.project, p.id));
    vscode.window.setStatusBarMessage(`$(beaker) Pipeline #${p.id}: ${action === 'retry' ? 'failed jobs retried' : 'canceled'}.`, 6000);
    await this.loadPipeline(s);
  }

  /** Opens the job log in a read-only editor (colors and section markers removed). */
  async showJobLog(arg: unknown): Promise<void> {
    const s = this.session;
    const job = ThreadTree.jobOf(arg) ?? ((arg as Job)?.web_url ? (arg as Job) : undefined);
    if (!s || !job) {
      throw new Error('No pipeline job selected.');
    }
    const uri = vscode.Uri.from({ scheme: LOG_SCHEME, path: `/${job.stage}/${job.name}.log`, query: String(job.id) });
    const key = uri.toString();
    this.logs.delete(key);
    const doc = await vscode.workspace.openTextDocument(uri);
    await vscode.languages.setTextDocumentLanguage(doc, 'log').then(undefined, () => undefined);
    await vscode.window.showTextDocument(doc, { preview: true, viewColumn: vscode.ViewColumn.Active });
    const raw = await s.client.jobTrace(s.project, job.id);
    const header = `# ${job.stage} / ${job.name} — ${statusText(job.status)}${job.duration ? ` in ${formatDuration(job.duration)}` : ''}\n# ${job.web_url}\n\n`;
    this.logs.set(key, header + (cleanJobLog(raw, 50 * 1024 * 1024).text || '(empty log)'));
    this.logChanged.fire(uri);
  }

  clearPipelineFindings(): void {
    this.pipelineRun?.cancel();
    if (this.pipelineThreads.length) {
      this.comments.clear('pipeline');
      this.pipelineThreads = [];
      this.tree.setThreads(this.threads);
      this.pipelineView.update(this.pipelineData());
    }
  }

  /** Reloads the pipeline on request (status bar, view title). */
  async refreshPipeline(): Promise<void> {
    if (!this.session) {
      await this.refresh();
      return;
    }
    await this.loadPipeline(this.session);
  }

  /** Quick pick with the jobs of the latest pipeline. */
  async showPipeline(): Promise<void> {
    if (!this.pipeline) {
      await this.refreshPipeline();
    }
    const info = this.pipeline;
    if (!info) {
      vscode.window.showInformationMessage('No pipeline for the merge request of the current branch.');
      return;
    }
    type Item = vscode.QuickPickItem & { job?: Job; action?: 'open' | 'refresh' };
    const items: Item[] = [
      { label: `$(link-external) Open pipeline #${info.pipeline.id} in browser`, description: statusText(info.pipeline.status), action: 'open' },
      { label: '$(refresh) Refresh pipeline status', action: 'refresh' },
      { label: 'Jobs', kind: vscode.QuickPickItemKind.Separator },
      ...info.jobs.map((j) => ({
        label: `$(${statusIcon(j.status)}) ${j.name}`,
        description: `${j.stage} · ${statusText(j.status)}${j.allow_failure && j.status === 'failed' ? ' (allowed to fail)' : ''}`,
        detail: j.status === 'failed' ? 'Enter: explain and fix with opencode' : 'Enter: open the job log in the browser',
        job: j,
      })),
    ];
    const pick = await vscode.window.showQuickPick(items, { placeHolder: `Pipeline #${info.pipeline.id}: ${statusText(info.pipeline.status)}` });
    if (!pick) {
      return;
    }
    if (pick.action === 'open') {
      await vscode.env.openExternal(vscode.Uri.parse(info.pipeline.web_url));
    } else if (pick.action === 'refresh') {
      await this.refreshPipeline();
    } else if (pick.job?.status === 'failed') {
      await this.fixPipeline(pick.job);
    } else if (pick.job) {
      await vscode.env.openExternal(vscode.Uri.parse(pick.job.web_url));
    }
  }

  async openJob(arg: unknown): Promise<void> {
    const job = ThreadTree.jobOf(arg) ?? (arg as Job | undefined);
    const url = job?.web_url ?? (await this.threadFrom(arg))?.jobUrl;
    if (url) {
      await vscode.env.openExternal(vscode.Uri.parse(url));
    }
  }

  /** "Explain and Fix" for a failed job: from the tree, the status bar list, a notification, or the command palette. */
  async fixPipeline(arg?: unknown): Promise<void> {
    let job = ThreadTree.jobOf(arg) ?? ((arg as Job)?.web_url ? (arg as Job) : undefined);
    if (!job) {
      if (!this.pipeline) {
        await this.refreshPipeline();
      }
      const failed = this.pipeline ? this.pipeline.jobs.filter((j) => j.status === 'failed') : [];
      if (!failed.length) {
        vscode.window.showInformationMessage('The latest pipeline has no failed job.');
        return;
      }
      if (failed.length === 1) {
        job = failed[0];
      } else {
        const pick = await vscode.window.showQuickPick(
          failed.map((j) => ({ label: j.name, description: j.stage, job: j })),
          { placeHolder: 'Which failed job?' },
        );
        job = pick?.job;
      }
    }
    if (!job) {
      return;
    }
    const target = job;
    const t = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: `opencode: analysing job "${target.name}"`, cancellable: true },
      (_p, token) => this.explainJob(target, token),
    );
    if (t.file) {
      await this.openThread(t);
    } else {
      await vscode.commands.executeCommand('gitlabCr.threads.focus');
    }
    vscode.window.showInformationMessage(
      `${target.name}: ${t.notes[0].body.split('\n').find((l) => l.trim() && !l.startsWith('**')) ?? 'see the GitLab CR view'}`,
    );
  }

  /** Runs opencode for the failed jobs in the background, except for jobs explained before. */
  private startAutoExplain(jobs: Job[], explicit = false): void {
    this.pipelineRun?.cancel();
    const cache = this.readJobCache();
    const max = explicit ? Infinity : vscode.workspace.getConfiguration('gitlabCr.pipeline').get<number>('maxJobs', 3);
    const todo: Job[] = [];
    for (const j of jobs) {
      const saved = cache[`pipeline-${j.id}`];
      if (saved && (!saved.dismissed || explicit) && !this.pipelineThreads.some((t) => t.id === `pipeline-${j.id}`)) {
        this.addPipelineThread(j, saved.body, saved.file, saved.line, saved.suggestion, saved.skipAutoFix);
      } else if (!saved && todo.length < max) {
        todo.push(j);
      }
    }
    if (!todo.length) {
      return;
    }
    const cts = new vscode.CancellationTokenSource();
    this.pipelineRun = cts;
    void vscode.window.withProgress(
      { location: vscode.ProgressLocation.Window, title: 'opencode: analysing the failed pipeline' },
      async () => {
        let ok = 0;
        let failed = 0;
        const concurrency = vscode.workspace.getConfiguration('gitlabCr.fixAll').get<number>('concurrency') ?? 2;
        await runPool(todo, concurrency, cts.token, async (j) => {
          try {
            await this.explainJob(j, cts.token);
            ok++;
          } catch (err) {
            if (!(err instanceof vscode.CancellationError)) {
              failed++;
              this.output.appendLine(`Cannot analyse job ${j.name}: ${(err as Error).message}`);
            }
          }
        });
        if (cts.token.isCancellationRequested || !ok) {
          return;
        }
        void vscode.window
          .showInformationMessage(
            `opencode explained ${ok} failed job(s)${failed ? `, ${failed} failed (see the "GitLab CR" output)` : ''}. See the GitLab CR view.`,
            'Show',
          )
          .then((c) => c && vscode.commands.executeCommand('gitlabCr.threads.focus'));
      },
    );
  }

  /** Gets the job log, asks opencode for the cause and a fix, and shows the result as a thread. */
  private async explainJob(job: Job, token?: vscode.CancellationToken): Promise<CrThread> {
    const s = this.session;
    if (!s?.mr) {
      throw new Error('No merge request loaded.');
    }
    const cfg = vscode.workspace.getConfiguration('gitlabCr');
    const remote = cfg.get<string>('remoteName') || 'origin';
    const maxBytes = Math.max(4, cfg.get<number>('pipeline.maxLogKb') ?? 48) * 1024;
    const raw = await s.client.jobTrace(s.project, job.id);
    const log = cleanJobLog(raw, maxBytes);
    if (!log.text.trim()) {
      throw new Error(`The log of job "${job.name}" is empty.`);
    }
    const baseBranch = cfg.get<string>('selfReview.baseBranch') || s.mr.target_branch || 'main';
    let files: string[] = [];
    try {
      files = await git.changedFiles(s.root, await git.mergeBase(s.root, baseBranch, remote));
    } catch (err) {
      this.output.appendLine(`Cannot list the changed files: ${(err as Error).message}`);
    }
    const prompt = pipelinePrompt({ name: job.name, stage: job.stage, failureReason: job.failure_reason }, log.text, log.truncated, files, baseBranch);
    const raw2 = await this.opencode.askJson<any>(s.root, prompt, token);
    const answer = validateFixAnswer(raw2);
    const cause = typeof raw2?.cause === 'string' && raw2.cause.trim() ? raw2.cause.trim() : answer.explanation || 'No cause given.';
    const edits = [];
    for (const e of answer.edits) {
      const target = await vscode.workspace.openTextDocument(vscode.Uri.file(resolveInRoot(s.root, e.file)));
      edits.push(prepareEdit(target, e));
    }
    const first = edits[0];
    const body = `**Pipeline #${this.pipeline?.pipeline.id ?? '?'}: job "${job.name}" (${job.stage}) failed.**\n\n${cause}`;
    return this.addPipelineThread(job, body, first?.file, first?.startLine, { explanation: answer.explanation, edits }, false);
  }

  private addPipelineThread(
    job: Job,
    body: string,
    file: string | undefined,
    line: number | undefined,
    suggestion: FixSuggestion | undefined,
    skipAutoFix: boolean,
  ): CrThread {
    const id = `pipeline-${job.id}`;
    const old = this.pipelineThreads.find((t) => t.id === id);
    if (old) {
      this.comments.remove(old);
      this.pipelineThreads = this.pipelineThreads.filter((t) => t !== old);
    }
    const exists = !!file && fs.existsSync(path.join(this.session?.root ?? '', file));
    const t = new CrThread(id, 'pipeline', [{ author: 'opencode CI analysis', body }], exists ? file : undefined, exists ? line : undefined, false, false);
    t.subtitle = job.name;
    t.jobUrl = job.web_url;
    t.suggestion = suggestion?.edits.length ? suggestion : undefined;
    t.skipAutoFix = skipAutoFix;
    this.pipelineThreads.push(t);
    this.comments.show([t]);
    this.tree.setThreads(this.threads);
    this.saveJobCache(t, false);
    this.pipelineView.update(this.pipelineData());
    return t;
  }

  /** Posts a self review finding to the merge request as a new discussion. */
  async postFinding(arg: unknown): Promise<void> {
    const t = await this.threadFrom(arg);
    if (!t || t.source !== 'self') {
      return;
    }
    if (!this.session?.mr) {
      await this.refresh();
    }
    const s = this.session;
    if (!s?.mr) {
      throw new Error('No open merge request for the current branch.');
    }
    const body = t.notes.map((n) => n.body).join('\n\n');
    const refs = s.mr.diff_refs;
    let posted = false;
    if (t.file && t.line && refs) {
      try {
        await s.client.createDiscussion(s.project, s.mr.iid, body, {
          position_type: 'text',
          base_sha: refs.base_sha,
          start_sha: refs.start_sha,
          head_sha: refs.head_sha,
          old_path: t.file,
          new_path: t.file,
          new_line: t.line,
        });
        posted = true;
      } catch (err) {
        // GitLab accepts a line position only on lines that are part of the pushed diff.
        this.output.appendLine(`Cannot post on the line, posting as a general note: ${(err as Error).message}`);
      }
    }
    if (!posted) {
      await s.client.createDiscussion(s.project, s.mr.iid, t.file ? `\`${t.location}\`\n\n${body}` : body);
    }
    this.comments.remove(t);
    this.selfThreads = this.selfThreads.filter((x) => x !== t);
    await this.refresh();
  }
}

function toThread(d: Discussion): CrThread | undefined {
  const notes = d.notes.filter((n) => !n.system);
  if (!notes.length) {
    return undefined;
  }
  const pos = notes[0].position;
  const file = pos && pos.position_type === 'text' ? pos.new_path ?? pos.old_path : undefined;
  const line = pos ? pos.new_line ?? pos.old_line ?? undefined : undefined;
  // Only lines on the new side can be moved to the local file.
  const mappable = !!file && !!pos?.new_line && !!pos?.head_sha;
  const resolvableNotes = notes.filter((n) => n.resolvable);
  const resolvable = resolvableNotes.length > 0;
  const resolved = resolvable && resolvableNotes.every((n) => n.resolved);
  const thread = new CrThread(
    d.id,
    'gitlab',
    notes.map((n) => ({
      id: n.id,
      authorId: n.author.id,
      author: n.author.name,
      avatarUrl: n.author.avatar_url,
      body: n.body,
      createdAt: n.created_at,
    })),
    file,
    file ? line ?? 1 : undefined,
    resolvable,
    resolved,
    d.id,
  );
  if (mappable) {
    thread.positionSha = pos!.head_sha;
    thread.gitlabLine = pos!.new_line!;
  }
  return thread;
}

/** Runs `fn` for each item, at most `concurrency` at a time, until the token is cancelled. */
export async function runPool<T>(
  items: T[],
  concurrency: number,
  token: vscode.CancellationToken,
  fn: (item: T) => Promise<void>,
): Promise<void> {
  let next = 0;
  const worker = async () => {
    while (next < items.length && !token.isCancellationRequested) {
      await fn(items[next++]);
    }
  };
  await Promise.all(Array.from({ length: Math.max(1, Math.min(concurrency, items.length)) }, worker));
}
