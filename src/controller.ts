import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { CommentManager, CrComment } from './comments';
import { applyGrouped, prepareEdit, resolveInRoot } from './edits';
import * as git from './git';
import { Discussion, GitLabClient, MergeRequest } from './gitlab';
import { CrThread } from './model';
import { OpencodeRunner } from './opencode';
import { numberLines, validateFixAnswer } from './parse';
import { fixPrompt, replyPrompt, selfReviewPrompt } from './prompts';
import { ThreadTree } from './tree';

const TOKEN_KEY = 'gitlabCr.token';
const CONTEXT_LINES = 40;

interface Session {
  root: string;
  project: string;
  baseUrl: string;
  client: GitLabClient;
  mr?: MergeRequest;
}

export class Controller implements vscode.Disposable {
  private session?: Session;
  private gitlabThreads: CrThread[] = [];
  private selfThreads: CrThread[] = [];
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
    this.comments = new CommentManager(() => this.root);
    this.treeView = vscode.window.createTreeView('gitlabCr.threads', { treeDataProvider: this.tree as any, showCollapseAll: true });
  }

  dispose(): void {
    this.comments.dispose();
    this.treeView.dispose();
  }

  private get threads(): CrThread[] {
    return [...this.gitlabThreads, ...this.selfThreads];
  }

  private update(t?: CrThread): void {
    if (t) {
      this.comments.render(t);
    }
    this.tree.refresh();
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

  async refresh(): Promise<void> {
    await vscode.window.withProgress(
      { location: { viewId: 'gitlabCr.threads' }, title: 'Loading CR notes' },
      async () => {
        const s = await this.connect();
        const branch = await git.currentBranch(s.root);
        s.mr = await s.client.findMergeRequest(s.project, branch);
        this.session = s;
        const previous = new Map(this.gitlabThreads.map((t) => [t.id, t]));

        this.comments.clear('gitlab');
        if (!s.mr) {
          this.gitlabThreads = [];
          this.treeView.message = `No open merge request for branch '${branch}' in ${s.project}.`;
        } else {
          const showResolved = vscode.workspace.getConfiguration('gitlabCr').get<boolean>('showResolved') ?? false;
          const discussions = await s.client.discussions(s.project, s.mr.iid);
          this.gitlabThreads = discussions
            .map((d) => toThread(d))
            .filter((t): t is CrThread => !!t && (showResolved || !t.resolved));
          for (const t of this.gitlabThreads) {
            // Keep suggestions and drafts made before the refresh.
            const old = previous.get(t.id);
            t.suggestion = old?.suggestion;
            t.draftReply = old?.draftReply;
          }
          const open = this.gitlabThreads.filter((t) => !t.resolved).length;
          this.treeView.message = `!${s.mr.iid} ${s.mr.title} (${open} open note${open === 1 ? '' : 's'})`;
        }
        this.comments.show(this.gitlabThreads);
        this.tree.setThreads(this.threads);
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
    this.update(t);
    vscode.window.setStatusBarMessage('$(check) Fix applied. Review and save the file.', 5000);
  }

  /** "Fix CR for all page": makes and applies fixes for every open note of the merge request. */
  async fixAll(): Promise<void> {
    let pool = this.gitlabThreads;
    if (this.selfThreads.length) {
      const pick = await vscode.window.showQuickPick(
        [
          { label: 'GitLab review notes', value: 'gitlab' },
          { label: 'Self review findings', value: 'self' },
          { label: 'Both', value: 'both' },
        ],
        { placeHolder: 'Which notes do you want to fix?' },
      );
      if (!pick) {
        return;
      }
      pool = pick.value === 'gitlab' ? this.gitlabThreads : pick.value === 'self' ? this.selfThreads : this.threads;
    }
    const targets = pool.filter((t) => t.file && t.line && !t.resolved);
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
        let next = 0;
        progress.report({ message: `0/${todo.length}` });
        const worker = async () => {
          while (next < todo.length && !token.isCancellationRequested) {
            const t = todo[next++];
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
          }
        };
        await Promise.all(Array.from({ length: Math.max(1, Math.min(concurrency, todo.length)) }, worker));
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
    t.notes.push({ author: note.author.name, avatarUrl: note.author.avatar_url, body: note.body, createdAt: note.created_at });
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

  async suggestReply(arg: unknown): Promise<void> {
    const t = await this.threadFrom(arg);
    if (!t) {
      return;
    }
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
    const answer = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'opencode: writing a reply', cancellable: true },
      (_p, token) => this.opencode.askJson<{ reply?: string }>(root, replyPrompt(t, context), token),
    );
    if (!answer.reply?.trim()) {
      throw new Error('opencode returned an empty reply.');
    }
    t.draftReply = answer.reply.trim();
    this.update(t);

    if (t.vsThread) {
      await this.openThread(t);
      return;
    }
    // General notes have no editor thread: let the user edit the draft in a document.
    const text = await this.editText(t.draftReply, `Suggested reply to: ${t.title}`);
    if (text?.trim()) {
      await this.postReply(t, text.trim());
      t.draftReply = undefined;
      this.update(t);
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
    await this.postReply(t, body);
    t.draftReply = undefined;
    this.update(t);
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
    } else if (c.kind === 'suggestion') {
      t.suggestion = undefined;
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
    if (!t || t.source !== 'self') {
      return;
    }
    this.comments.remove(t);
    this.selfThreads = this.selfThreads.filter((x) => x !== t);
    this.tree.setThreads(this.threads);
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
  const resolvableNotes = notes.filter((n) => n.resolvable);
  const resolvable = resolvableNotes.length > 0;
  const resolved = resolvable && resolvableNotes.every((n) => n.resolved);
  return new CrThread(
    d.id,
    'gitlab',
    notes.map((n) => ({ author: n.author.name, avatarUrl: n.author.avatar_url, body: n.body, createdAt: n.created_at })),
    file,
    file ? line ?? 1 : undefined,
    resolvable,
    resolved,
    d.id,
  );
}
