import * as vscode from 'vscode';
import { Job, Pipeline, PipelineStatus } from './gitlab';

export interface PipelineInfo {
  pipeline: Pipeline;
  jobs: Job[];
}

export const ACTIVE_STATUSES: PipelineStatus[] = ['created', 'waiting_for_resource', 'preparing', 'pending', 'running'];

export function isActive(status: PipelineStatus): boolean {
  return ACTIVE_STATUSES.includes(status);
}

export function statusIcon(status: PipelineStatus): string {
  switch (status) {
    case 'success':
      return 'check';
    case 'failed':
      return 'error';
    case 'running':
      return 'sync~spin';
    case 'canceled':
      return 'circle-slash';
    case 'skipped':
      return 'debug-step-over';
    case 'manual':
      return 'play';
    case 'scheduled':
      return 'calendar';
    default:
      return 'clock';
  }
}

export function statusText(status: PipelineStatus): string {
  return status.replace(/_/g, ' ');
}

/** Jobs that made the pipeline fail (failed jobs that are not allowed to fail). */
export function failedJobs(info: PipelineInfo): Job[] {
  return info.jobs.filter((j) => j.status === 'failed' && !j.allow_failure);
}

/** Status bar item "CI: status", with the failed jobs in the tooltip. */
export class PipelineStatusBar implements vscode.Disposable {
  private readonly item: vscode.StatusBarItem;

  constructor() {
    this.item = vscode.window.createStatusBarItem('gitlabCr.pipeline', vscode.StatusBarAlignment.Left, 40);
    this.item.name = 'GitLab CR: pipeline';
    this.item.command = 'gitlabCr.showPipeline';
  }

  update(info: PipelineInfo | undefined, mrIid?: number): void {
    if (!info) {
      this.item.hide();
      return;
    }
    const { pipeline, jobs } = info;
    const failed = failedJobs(info);
    this.item.text = `$(${statusIcon(pipeline.status)}) CI: ${statusText(pipeline.status)}${failed.length ? ` (${failed.length})` : ''}`;
    const md = new vscode.MarkdownString(`**Pipeline #${pipeline.id}** of !${mrIid ?? '?'}: ${statusText(pipeline.status)}\n\n`);
    for (const j of jobs) {
      md.appendMarkdown(`$(${statusIcon(j.status)}) ${j.stage} / ${j.name}${j.allow_failure && j.status === 'failed' ? ' (allowed to fail)' : ''}\n\n`);
    }
    md.appendMarkdown('_Click for the job list._');
    md.supportThemeIcons = true;
    this.item.tooltip = md;
    this.item.backgroundColor =
      pipeline.status === 'failed'
        ? new vscode.ThemeColor('statusBarItem.errorBackground')
        : pipeline.status === 'canceled'
          ? new vscode.ThemeColor('statusBarItem.warningBackground')
          : undefined;
    this.item.show();
  }

  dispose(): void {
    this.item.dispose();
  }
}
