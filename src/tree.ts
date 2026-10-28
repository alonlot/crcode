import * as vscode from 'vscode';
import { CrThread } from './model';

class NoteNode {
  constructor(readonly thread: CrThread, readonly label: string, readonly detail: string, readonly icon: string) {}
}

type Node = CrThread | NoteNode;

export class ThreadTree implements vscode.TreeDataProvider<Node> {
  private readonly changed = new vscode.EventEmitter<Node | undefined>();
  readonly onDidChangeTreeData = this.changed.event;
  private threads: CrThread[] = [];

  setThreads(threads: CrThread[]): void {
    this.threads = threads;
    this.changed.fire(undefined);
  }

  refresh(): void {
    this.changed.fire(undefined);
  }

  getChildren(element?: Node): Node[] {
    if (!element) {
      // GitLab notes first, then self review findings; each group sorted by file and line.
      return [...this.threads].sort((a, b) =>
        a.source !== b.source
          ? a.source === 'gitlab'
            ? -1
            : 1
          : (a.file ?? '').localeCompare(b.file ?? '') || (a.line ?? 0) - (b.line ?? 0),
      );
    }
    if (element instanceof NoteNode) {
      return [];
    }
    const nodes: Node[] = element.notes.map(
      (n) => new NoteNode(element, n.author, n.body.replace(/\s+/g, ' ').slice(0, 200), 'comment'),
    );
    if (element.suggestion) {
      nodes.push(
        new NoteNode(element, 'opencode: suggested fix', element.suggestion.explanation || `${element.suggestion.edits.length} edit(s)`, 'lightbulb'),
      );
    }
    if (element.draftReply !== undefined) {
      nodes.push(new NoteNode(element, 'opencode: suggested reply', element.draftReply.replace(/\s+/g, ' '), 'sparkle'));
    }
    return nodes;
  }

  getTreeItem(element: Node): vscode.TreeItem {
    if (element instanceof NoteNode) {
      const item = new vscode.TreeItem(element.label, vscode.TreeItemCollapsibleState.None);
      item.description = element.detail;
      item.tooltip = element.detail;
      item.iconPath = new vscode.ThemeIcon(element.icon);
      item.command = { command: 'gitlabCr.openThread', title: 'Open', arguments: [element.thread] };
      return item;
    }
    const t = element;
    const item = new vscode.TreeItem(t.title, vscode.TreeItemCollapsibleState.Collapsed);
    item.description = t.outdated ? `${t.location} (outdated)` : t.location;
    item.tooltip = new vscode.MarkdownString(`**${t.location}**\n\n${t.notes.map((n) => `**${n.author}:** ${n.body}`).join('\n\n')}`);
    item.contextValue = t.contextValue;
    item.iconPath = new vscode.ThemeIcon(
      t.source === 'self' ? 'checklist' : t.resolved ? 'pass-filled' : t.suggestion ? 'lightbulb' : 'comment-discussion',
    );
    item.command = { command: 'gitlabCr.openThread', title: 'Open', arguments: [t] };
    return item;
  }

  /** Allows commands invoked from a child node to find the thread. */
  static threadOf(arg: unknown): CrThread | undefined {
    if (arg instanceof CrThread) {
      return arg;
    }
    if (arg instanceof NoteNode) {
      return arg.thread;
    }
    return undefined;
  }
}
