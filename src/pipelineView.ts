import * as vscode from 'vscode';
import { CiStage, ShownStatus, formatDuration, statusLabel } from './ciModel';

export interface PipelineViewJob {
  id: number;
  name: string;
  status: ShownStatus;
  rawStatus: string;
  duration: string;
  queued: string;
  failureReason?: string;
  webUrl: string;
  actions: string[];
  hasFinding: boolean;
}

export interface PipelineViewData {
  pipeline: {
    id: number;
    status: ShownStatus;
    ref: string;
    sha: string;
    webUrl: string;
    duration: string;
    createdAt?: string;
    user?: string;
    source?: string;
  };
  mrIid?: number;
  mrTitle?: string;
  stages: { name: string; status: ShownStatus; jobs: PipelineViewJob[] }[];
  counts: { total: number; failed: number; running: number; success: number };
  updatedAt: string;
}

export type PipelineViewMessage =
  | { type: 'openUrl'; url: string }
  | { type: 'refresh' }
  | { type: 'log' | 'fix' | 'retry' | 'play' | 'cancel' | 'finding'; jobId: number }
  | { type: 'retryPipeline' | 'cancelPipeline' | 'explainFailed' };

/** The pipeline graph panel: one column per stage, one pill per job, as on the GitLab merge request page. */
export class PipelineView implements vscode.Disposable {
  private panel?: vscode.WebviewPanel;
  private last?: PipelineViewData;

  constructor(
    private readonly ctx: vscode.ExtensionContext,
    private readonly onMessage: (m: PipelineViewMessage) => Promise<void>,
  ) {}

  get isOpen(): boolean {
    return !!this.panel;
  }

  show(data: PipelineViewData | undefined): void {
    if (!this.panel) {
      this.panel = vscode.window.createWebviewPanel('gitlabCr.pipelineGraph', 'Pipeline', vscode.ViewColumn.Beside, {
        enableScripts: true,
        retainContextWhenHidden: true,
      });
      this.panel.iconPath = vscode.Uri.joinPath(this.ctx.extensionUri, 'media', 'icon.svg');
      this.panel.webview.html = html(this.panel.webview);
      this.panel.webview.onDidReceiveMessage((m: PipelineViewMessage) => void this.onMessage(m));
      this.panel.onDidDispose(() => (this.panel = undefined));
    } else {
      this.panel.reveal(undefined, true);
    }
    this.update(data);
  }

  update(data: PipelineViewData | undefined): void {
    this.last = data;
    if (this.panel) {
      this.panel.title = data ? `Pipeline #${data.pipeline.id}` : 'Pipeline';
      void this.panel.webview.postMessage({ type: 'data', data });
    }
  }

  /** For tests: the data last sent to the panel. */
  get data(): PipelineViewData | undefined {
    return this.last;
  }

  dispose(): void {
    this.panel?.dispose();
  }
}

export function toViewStage(s: CiStage, extra: (jobId: number) => { actions: string[]; hasFinding: boolean }): PipelineViewData['stages'][number] {
  return {
    name: s.name,
    status: s.status,
    jobs: s.jobs.map((j) => ({
      id: j.id,
      name: j.name,
      status: j.status === 'failed' && j.allow_failure ? 'warning' : j.status,
      rawStatus: j.status,
      duration: formatDuration(j.duration),
      queued: formatDuration(j.queued_duration),
      failureReason: j.failure_reason,
      webUrl: j.web_url,
      ...extra(j.id),
    })),
  };
}

function html(webview: vscode.Webview): string {
  const nonce = Math.random().toString(36).slice(2) + Date.now().toString(36);
  const labels = JSON.stringify(
    Object.fromEntries(
      (['created', 'waiting_for_resource', 'preparing', 'pending', 'running', 'success', 'failed', 'canceled', 'skipped', 'manual', 'scheduled', 'warning'] as ShownStatus[]).map((s) => [s, statusLabel(s)]),
    ),
  );
  return /* html */ `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${webview.cspSource} 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Pipeline</title>
<style nonce="${nonce}">
  :root {
    --ci-success: #108548; --ci-failed: #dd2b0e; --ci-running: #1f75cb; --ci-warning: #ab6100;
    --ci-pending: #ab6100; --ci-neutral: #89888d; --ci-canceled: #89888d; --ci-manual: #89888d;
    --pill-bg: var(--vscode-editorWidget-background, var(--vscode-editor-background));
    --pill-border: var(--vscode-widget-border, var(--vscode-panel-border, #8884));
  }
  body { font-family: var(--vscode-font-family); font-size: var(--vscode-font-size); color: var(--vscode-foreground); background: var(--vscode-editor-background); padding: 12px 16px; }
  a { color: var(--vscode-textLink-foreground); text-decoration: none; }
  a:hover { text-decoration: underline; }
  button { font: inherit; color: var(--vscode-button-secondaryForeground); background: var(--vscode-button-secondaryBackground); border: 1px solid var(--pill-border); border-radius: 4px; padding: 3px 10px; cursor: pointer; }
  button:hover { background: var(--vscode-button-secondaryHoverBackground); }
  button.primary { color: var(--vscode-button-foreground); background: var(--vscode-button-background); border-color: transparent; }
  button.primary:hover { background: var(--vscode-button-hoverBackground); }
  button.danger { color: #fff; background: var(--ci-failed); border-color: transparent; }
  .empty { opacity: .8; padding: 24px 0; }
  header { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 14px; padding-bottom: 12px; border-bottom: 1px solid var(--pill-border); margin-bottom: 16px; }
  .badge { display: inline-flex; align-items: center; gap: 6px; padding: 3px 10px 3px 6px; border-radius: 12px; font-weight: 600; color: #fff; }
  .meta { display: flex; flex-wrap: wrap; gap: 4px 12px; opacity: .9; }
  .meta code { font-family: var(--vscode-editor-font-family); font-size: .95em; }
  .spacer { flex: 1; }
  .actions { display: flex; gap: 6px; }
  .counts { display: flex; gap: 10px; margin: -6px 0 14px; opacity: .85; font-size: .95em; }
  .counts span { display: inline-flex; align-items: center; gap: 4px; }
  .graph { display: flex; align-items: flex-start; overflow-x: auto; padding: 4px 0 16px; }
  .stage { min-width: 190px; max-width: 280px; position: relative; }
  .stage + .stage { margin-left: 44px; }
  .stage + .stage::before { content: ""; position: absolute; left: -44px; top: 50px; width: 44px; border-top: 2px solid var(--pill-border); }
  .stage-head { display: flex; align-items: center; gap: 8px; font-weight: 600; padding: 4px 6px 10px; text-transform: none; }
  .stage-head .n { opacity: .6; font-weight: 400; }
  .job { position: relative; display: flex; align-items: center; gap: 8px; padding: 6px 8px; margin: 6px 0; border: 1px solid var(--pill-border); border-radius: 6px; background: var(--pill-bg); cursor: default; }
  .job:hover { border-color: var(--vscode-focusBorder); }
  .job .name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .job .dur { opacity: .65; font-size: .9em; white-space: nowrap; }
  .job .tools { display: none; position: absolute; right: 4px; top: 50%; transform: translateY(-50%); gap: 2px; background: var(--pill-bg); padding-left: 6px; }
  .job:hover .tools { display: flex; }
  .job .tools button { padding: 1px 6px; min-width: 24px; line-height: 18px; }
  .job.has-finding .name::after { content: " 💡"; }
  .job .reason { position: absolute; left: 0; right: 0; top: 100%; z-index: 2; display: none; padding: 6px 8px; font-size: .9em; background: var(--vscode-editorHoverWidget-background); border: 1px solid var(--vscode-editorHoverWidget-border); border-radius: 4px; }
  .job:hover .reason { display: block; }
  svg.ci { width: 16px; height: 16px; flex: none; }
  svg.ci.big { width: 22px; height: 22px; }
  .st-success { color: var(--ci-success); } .st-failed { color: var(--ci-failed); } .st-running { color: var(--ci-running); }
  .st-warning, .st-pending { color: var(--ci-warning); }
  .st-created, .st-waiting_for_resource, .st-preparing, .st-canceled, .st-skipped, .st-manual, .st-scheduled { color: var(--ci-neutral); }
  .bg-success { background: var(--ci-success); } .bg-failed { background: var(--ci-failed); } .bg-running { background: var(--ci-running); }
  .bg-warning, .bg-pending { background: var(--ci-warning); }
  .bg-created, .bg-waiting_for_resource, .bg-preparing, .bg-canceled, .bg-skipped, .bg-manual, .bg-scheduled { background: var(--ci-neutral); }
  @keyframes spin { to { transform: rotate(360deg); } }
  .st-running svg.ci, svg.ci.st-running { animation: spin 1.6s linear infinite; }
  footer { opacity: .6; font-size: .9em; margin-top: 8px; }
</style>
</head>
<body>
<div id="app"><div class="empty">Loading the pipeline…</div></div>
<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  const LABELS = ${labels};
  const ICONS = {
    success: '<path d="M6.5 10.6 4.2 8.3l-1 1 3.3 3.3 6.3-6.3-1-1z"/>',
    failed: '<path d="M4.6 3.5 3.5 4.6 6.9 8l-3.4 3.4 1.1 1.1L8 9.1l3.4 3.4 1.1-1.1L9.1 8l3.4-3.4-1.1-1.1L8 6.9z"/>',
    warning: '<path d="M7.2 3.8h1.6v5H7.2zM8 12.4a1 1 0 1 1 0-2 1 1 0 0 1 0 2z"/>',
    running: '<path d="M8 3a5 5 0 1 1-4.9 6h1.6A3.5 3.5 0 1 0 8 4.5z"/>',
    pending: '<path d="M5.2 4.5h1.8v7H5.2zm3.8 0h1.8v7H9z"/>',
    created: '<circle cx="8" cy="8" r="2.2"/>',
    waiting_for_resource: '<circle cx="8" cy="8" r="2.2"/>',
    preparing: '<circle cx="4.5" cy="8" r="1.3"/><circle cx="8" cy="8" r="1.3"/><circle cx="11.5" cy="8" r="1.3"/>',
    canceled: '<path d="M4.3 10.6 10.6 4.3l1.1 1.1-6.3 6.3z"/>',
    skipped: '<path d="m5.2 4.5 3.5 3.5-3.5 3.5-1-1L6.7 8 4.2 5.5zm4 0 3.5 3.5-3.5 3.5-1-1L10.7 8 8.2 5.5z"/>',
    manual: '<path d="M8 5.3a2.7 2.7 0 1 0 0 5.4 2.7 2.7 0 0 0 0-5.4zm0 1.5a1.2 1.2 0 1 1 0 2.4 1.2 1.2 0 0 1 0-2.4zM7.3 3h1.4v1.6H7.3zm0 8.4h1.4V13H7.3zM3 7.3h1.6v1.4H3zm8.4 0H13v1.4h-1.6zM4.5 5.5l1-1 1.1 1.1-1 1zm5 5 1-1 1.1 1.1-1 1zm-5 0 1.1-1.1 1 1-1.1 1.1zm5-5 1.1-1.1 1 1-1.1 1.1z"/>',
    scheduled: '<path d="M7.3 4.5h1.4v3.3l2.2 1.3-.7 1.2-2.9-1.7z"/>',
  };
  const icon = (status, big) => '<svg class="ci st-' + status + (big ? ' big' : '') + '" viewBox="0 0 16 16" aria-label="' + status + '"><circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" stroke-width="1.6"/><g fill="currentColor">' + (ICONS[status] || ICONS.created) + '</g></svg>';
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  const label = (s) => LABELS[s] || s;
  const TOOL = { log: ['▤', 'Show job log'], fix: ['💡', 'Explain and fix with opencode'], retry: ['↻', 'Retry job'], play: ['▶', 'Run manual job'], cancel: ['■', 'Cancel job'], open: ['↗', 'Open job in GitLab'] };

  function render(d) {
    const app = document.getElementById('app');
    if (!d) {
      app.innerHTML = '<div class="empty">No pipeline for the merge request of the current branch.</div><button id="refresh">Refresh</button>';
      document.getElementById('refresh').onclick = () => vscode.postMessage({ type: 'refresh' });
      return;
    }
    const p = d.pipeline;
    const headActions = [
      '<button class="primary" data-act="refresh">Refresh</button>',
      p.status === 'failed' || p.status === 'canceled' ? '<button data-act="retryPipeline">Retry failed jobs</button>' : '',
      p.status === 'running' || p.status === 'pending' ? '<button class="danger" data-act="cancelPipeline">Cancel pipeline</button>' : '',
      d.counts.failed ? '<button data-act="explainFailed">Explain failed jobs</button>' : '',
      '<button data-act="openPipeline">Open in GitLab</button>',
    ].join('');
    app.innerHTML =
      '<header>' +
        '<span class="badge bg-' + p.status + '">' + icon(p.status) + esc(label(p.status)) + '</span>' +
        '<div class="meta">' +
          '<span>Pipeline <a href="#" data-url="' + esc(p.webUrl) + '">#' + p.id + '</a>' + (d.mrIid ? ' for !' + d.mrIid : '') + '</span>' +
          '<span>' + esc(p.ref) + ' <code>' + esc(p.sha.slice(0, 8)) + '</code></span>' +
          (p.duration ? '<span>⏱ ' + esc(p.duration) + '</span>' : '') +
          (p.user ? '<span>by ' + esc(p.user) + '</span>' : '') +
          (p.source ? '<span>' + esc(p.source.replace(/_/g, ' ')) + '</span>' : '') +
        '</div><span class="spacer"></span><div class="actions">' + headActions + '</div>' +
      '</header>' +
      '<div class="counts"><span>' + d.counts.total + ' jobs</span>' +
        (d.counts.success ? '<span class="st-success">' + icon('success') + d.counts.success + ' passed</span>' : '') +
        (d.counts.failed ? '<span class="st-failed">' + icon('failed') + d.counts.failed + ' failed</span>' : '') +
        (d.counts.running ? '<span class="st-running">' + icon('running') + d.counts.running + ' running</span>' : '') +
      '</div>' +
      '<div class="graph">' + d.stages.map((s) =>
        '<div class="stage"><div class="stage-head">' + icon(s.status) + '<span>' + esc(s.name) + '</span><span class="n">' + s.jobs.length + '</span></div>' +
        s.jobs.map((j) =>
          '<div class="job' + (j.hasFinding ? ' has-finding' : '') + '" data-job="' + j.id + '" title="' + esc(j.name + ': ' + label(j.status) + (j.queued ? ' · queued ' + j.queued : '')) + '">' +
            icon(j.status) + '<span class="name">' + esc(j.name) + '</span><span class="dur">' + esc(j.duration) + '</span>' +
            '<span class="tools">' +
              (j.hasFinding ? '<button data-act="finding" data-job="' + j.id + '" title="Show the opencode finding">💬</button>' : '') +
              ['log', j.rawStatus === 'failed' ? 'fix' : '', ...j.actions, 'open'].filter(Boolean).map((a) =>
                '<button data-act="' + a + '" data-job="' + j.id + '" title="' + TOOL[a][1] + '">' + TOOL[a][0] + '</button>').join('') +
            '</span>' +
            (j.failureReason ? '<div class="reason">' + esc(label(j.status)) + ': ' + esc(j.failureReason.replace(/_/g, ' ')) + '</div>' : '') +
          '</div>').join('') +
        '</div>').join('') +
      '</div>' +
      '<footer>Updated ' + esc(d.updatedAt) + '</footer>';
    app.querySelectorAll('[data-act]').forEach((b) => (b.onclick = (e) => {
      e.stopPropagation();
      const act = b.dataset.act;
      if (act === 'openPipeline') { vscode.postMessage({ type: 'openUrl', url: p.webUrl }); return; }
      if (act === 'open') { const j = findJob(d, b.dataset.job); vscode.postMessage({ type: 'openUrl', url: j.webUrl }); return; }
      vscode.postMessage(b.dataset.job ? { type: act, jobId: Number(b.dataset.job) } : { type: act });
    }));
    app.querySelectorAll('[data-url]').forEach((a) => (a.onclick = (e) => { e.preventDefault(); vscode.postMessage({ type: 'openUrl', url: a.dataset.url }); }));
    app.querySelectorAll('.job').forEach((el) => (el.ondblclick = () => vscode.postMessage({ type: 'log', jobId: Number(el.dataset.job) })));
  }
  const findJob = (d, id) => d.stages.flatMap((s) => s.jobs).find((j) => String(j.id) === String(id));
  window.addEventListener('message', (e) => { if (e.data.type === 'data') { render(e.data.data); vscode.setState(e.data.data); } });
  const saved = vscode.getState();
  if (saved) render(saved);
</script>
</body>
</html>`;
}
