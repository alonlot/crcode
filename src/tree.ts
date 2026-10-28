import * as vscode from 'vscode';
import { CiStage, groupStages } from './ciModel';
import { Job } from './gitlab';
import { CrThread } from './model';
import { PipelineInfo, failedJobs, statusIcon, statusText } from './pipeline';

class NoteNode {
  constructor(readonly thread: CrThread, readonly label: string, readonly detail: string, readonly icon: string) {}
}

export class PipelineNode {
  constructor(readonly info: PipelineInfo) {}
}

export class StageNode {
  constructor(readonly stage: CiStage) {}
}

export class JobNode {
  constructor(readonly job: Job) {}
}

type Node = CrThread | NoteNode | PipelineNode | StageNode | JobNode;

export class ThreadTree implements vscode.TreeDataProvider<Node> {
  private readonly changed = new vscode.EventEmitter<Node | undefined>();
  readonly onDidChangeTreeData = this.changed.event;
  private threads: CrThread[] = [];
  private pipeline?: PipelineInfo;

  setThreads(threads: CrThread[]): void {
    this.threads = threads;
    this.changed.fire(undefined);
  }

  setPipeline(info: PipelineInfo | undefined): void {
    this.pipeline = info;
    this.changed.fire(undefined);
  }

  refresh(): void {
    this.changed.fire(undefined);
  }

  getChildren(element?: Node): Node[] {
    if (!element) {
      // Pipeline first, then CI failures, GitLab notes and self review findings; each group sorted by file and line.
      const order: Record<string, number> = { pipeline: 0, gitlab: 1, self: 2 };
      const threads = [...this.threads].sort(
        (a, b) =>
          order[a.source] - order[b.source] || (a.file ?? '').localeCompare(b.file ?? '') || (a.line ?? 0) - (b.line ?? 0),
      );
      return this.pipeline ? [new PipelineNode(this.pipeline), ...threads] : threads;
    }
    if (element instanceof NoteNode || element instanceof JobNode) {
      return [];
    }
    if (element instanceof PipelineNode) {
      return groupStages(element.info.jobs).map((st) => new StageNode(st));
    }
    if (element instanceof StageNode) {
      return element.stage.jobs.map((j) => new JobNode(j as Job));
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
    if (element instanceof PipelineNode) {
      const { pipeline } = element.info;
      const failed = failedJobs(element.info);
      const item = new vscode.TreeItem(`Pipeline #${pipeline.id}: ${statusText(pipeline.status)}`, vscode.TreeItemCollapsibleState.Expanded);
      item.description = failed.length ? `${failed.length} job(s) failed` : `${element.info.jobs.length} job(s)`;
      item.iconPath = new vscode.ThemeIcon(statusIcon(pipeline.status));
      item.contextValue = 'pipeline';
      item.tooltip = `${pipeline.web_url}\nClick for the pipeline graph`;
      item.command = { command: 'gitlabCr.openPipelineView', title: 'Show Pipeline Graph' };
      return item;
    }
    if (element instanceof StageNode) {
      const st = element.stage;
      const item = new vscode.TreeItem(st.name, vscode.TreeItemCollapsibleState.Expanded);
      const failed = st.jobs.filter((j) => j.status === 'failed' && !j.allow_failure).length;
      item.description = `${statusText(st.status === 'warning' ? 'failed' : st.status)}${st.status === 'warning' ? ' (allowed)' : ''} · ${st.jobs.length} job(s)${failed ? `, ${failed} failed` : ''}`;
      item.iconPath = new vscode.ThemeIcon(st.status === 'warning' ? 'warning' : statusIcon(st.status));
      item.contextValue = 'stage';
      return item;
    }
    if (element instanceof JobNode) {
      const j = element.job;
      const item = new vscode.TreeItem(j.name, vscode.TreeItemCollapsibleState.None);
      item.description = `${j.stage} · ${statusText(j.status)}${j.allow_failure && j.status === 'failed' ? ' (allowed)' : ''}`;
      item.iconPath = new vscode.ThemeIcon(statusIcon(j.status));
      item.contextValue = `job job-${j.status}`;
      item.tooltip = j.failure_reason ? `${j.web_url}\n${j.failure_reason}` : j.web_url;
      item.command = { command: 'gitlabCr.showJobLog', title: 'Show Job Log', arguments: [element] };
      return item;
    }
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
      t.source === 'self' ? 'checklist' : t.source === 'pipeline' ? 'beaker' : t.resolved ? 'pass-filled' : t.suggestion ? 'lightbulb' : 'comment-discussion',
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

  static jobOf(arg: unknown): Job | undefined {
    return arg instanceof JobNode ? arg.job : undefined;
  }
}
