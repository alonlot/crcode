import * as path from 'path';
import * as vscode from 'vscode';
import { CrThread } from './model';
import { renderDiff } from './parse';

export type CrCommentKind = 'note' | 'suggestion' | 'draft';

let nextCommentId = 1;

export class CrComment implements vscode.Comment {
  readonly id = nextCommentId++;
  mode = vscode.CommentMode.Preview;
  savedBody: string;
  body: string | vscode.MarkdownString;

  constructor(
    readonly crThread: CrThread,
    readonly kind: CrCommentKind,
    readonly author: vscode.CommentAuthorInformation,
    text: string,
    readonly label?: string,
    readonly timestamp?: Date,
  ) {
    this.savedBody = text;
    this.body = markdown(text);
  }

  get contextValue(): string {
    return this.kind;
  }
}

function markdown(text: string): vscode.MarkdownString {
  const md = new vscode.MarkdownString(text);
  md.supportHtml = false;
  md.isTrusted = false;
  return md;
}

export function suggestionMarkdown(thread: CrThread): string {
  const s = thread.suggestion;
  if (!s) {
    return '';
  }
  const parts = [`**Suggested fix:** ${s.explanation || '(no explanation)'}`];
  if (!s.edits.length) {
    parts.push('_opencode suggests no code change._');
  }
  for (const e of s.edits) {
    parts.push(`\`${e.file}\`\n\n\`\`\`diff\n${renderDiff(e.oldText.replace(/\r?\n$/, ''), e.newText, e.startLine)}\n\`\`\``);
  }
  return parts.join('\n\n');
}

export class CommentManager implements vscode.Disposable {
  readonly controller: vscode.CommentController;
  private readonly byVsThread = new Map<vscode.CommentThread, CrThread>();

  constructor(private readonly root: () => string | undefined) {
    this.controller = vscode.comments.createCommentController('gitlabCr', 'GitLab CR');
    this.controller.options = { prompt: 'Reply to this note on GitLab…', placeHolder: 'Write a reply' };
  }

  dispose(): void {
    this.clear();
    this.controller.dispose();
  }

  clear(source?: CrThread['source']): void {
    for (const [vs, cr] of [...this.byVsThread]) {
      if (!source || cr.source === source) {
        vs.dispose();
        cr.vsThread = undefined;
        this.byVsThread.delete(vs);
      }
    }
  }

  lookup(vsThread: vscode.CommentThread): CrThread | undefined {
    return this.byVsThread.get(vsThread);
  }

  show(threads: CrThread[]): void {
    const root = this.root();
    if (!root) {
      return;
    }
    for (const t of threads) {
      if (!t.file || !t.line) {
        continue;
      }
      const uri = vscode.Uri.file(path.join(root, t.file));
      const line = Math.max(0, t.line - 1);
      const vs = this.controller.createCommentThread(uri, new vscode.Range(line, 0, line, 0), []);
      vs.label =
        t.source === 'self'
          ? `Self review${t.severity ? ' · ' + t.severity : ''}`
          : t.source === 'pipeline'
            ? `CI failure${t.subtitle ? ' · ' + t.subtitle : ''}`
            : t.outdated
            ? `GitLab CR · outdated (the code changed, was line ${t.gitlabLine})`
            : 'GitLab CR';
      vs.collapsibleState = t.resolved
        ? vscode.CommentThreadCollapsibleState.Collapsed
        : vscode.CommentThreadCollapsibleState.Expanded;
      vs.canReply = t.source === 'gitlab';
      t.vsThread = vs;
      this.byVsThread.set(vs, t);
      this.render(t);
    }
  }

  remove(t: CrThread): void {
    if (t.vsThread) {
      this.byVsThread.delete(t.vsThread);
      t.vsThread.dispose();
      t.vsThread = undefined;
    }
  }

  /** Rebuilds the comments of one thread from its current state. */
  render(t: CrThread): void {
    const vs = t.vsThread;
    if (!vs) {
      return;
    }
    const comments: CrComment[] = t.notes.map(
      (n) =>
        new CrComment(
          t,
          'note',
          { name: n.author, iconPath: n.avatarUrl?.startsWith('http') ? vscode.Uri.parse(n.avatarUrl) : undefined },
          n.body,
          undefined,
          n.createdAt ? new Date(n.createdAt) : undefined,
        ),
    );
    if (t.suggestion) {
      comments.push(new CrComment(t, 'suggestion', { name: 'opencode' }, suggestionMarkdown(t), 'suggested fix'));
    }
    if (t.draftReply !== undefined) {
      comments.push(new CrComment(t, 'draft', { name: 'opencode' }, t.draftReply, 'suggested reply (draft)'));
    }
    vs.comments = comments;
    vs.contextValue = t.contextValue;
    if (t.resolvable) {
      vs.state = t.resolved ? vscode.CommentThreadState.Resolved : vscode.CommentThreadState.Unresolved;
    }
  }
}
