import * as vscode from 'vscode';
import { Controller } from './controller';

export function activate(ctx: vscode.ExtensionContext): { controller: Controller } {
  const output = vscode.window.createOutputChannel('GitLab CR');
  const controller = new Controller(ctx, output);
  ctx.subscriptions.push(output, controller);

  const register = (id: string, fn: (...args: any[]) => unknown) =>
    ctx.subscriptions.push(
      vscode.commands.registerCommand(id, async (...args: any[]) => {
        try {
          return await fn(...args);
        } catch (err) {
          if (err instanceof vscode.CancellationError) {
            return;
          }
          const msg = err instanceof Error ? err.message : String(err);
          output.appendLine(`[${id}] ${msg}`);
          vscode.window.showErrorMessage(msg, 'Show Log').then((pick) => pick && output.show());
        }
      }),
    );

  register('gitlabCr.refresh', () => controller.refresh());
  register('gitlabCr.setToken', () => controller.setToken());
  register('gitlabCr.openInBrowser', () => controller.openInBrowser());
  register('gitlabCr.openThread', (t) => controller.openThread(t));
  register('gitlabCr.suggestFix', (arg) => controller.suggestFix(arg));
  register('gitlabCr.applyFix', (arg) => controller.applyFix(arg));
  register('gitlabCr.previewFix', (arg) => controller.previewFix(arg));
  register('gitlabCr.applyFromPreview', (uri) => controller.applyFromPreview(uri));
  register('gitlabCr.fixAll', () => controller.fixAll());
  register('gitlabCr.reply', (arg) => controller.reply(arg));
  register('gitlabCr.suggestReply', (arg) => controller.suggestReply(arg));
  register('gitlabCr.sendDraft', (c) => controller.sendDraft(c));
  register('gitlabCr.editDraft', (c) => controller.editDraft(c));
  register('gitlabCr.saveDraft', (c) => controller.saveDraft(c));
  register('gitlabCr.cancelDraftEdit', (c) => controller.cancelDraftEdit(c));
  register('gitlabCr.discardDraft', (c) => controller.discard(c));
  register('gitlabCr.toggleResolved', (arg) => controller.toggleResolved(arg));
  register('gitlabCr.selfReview', () => controller.selfReview());
  register('gitlabCr.clearSelfReview', () => controller.clearSelfReview());
  register('gitlabCr.postFinding', (arg) => controller.postFinding(arg));
  register('gitlabCr.dismissFinding', (arg) => controller.dismissFinding(arg));
  register('gitlabCr.writeCommitMessage', () => controller.writeCommitMessage());
  register('gitlabCr.showPipeline', () => controller.showPipeline());
  register('gitlabCr.refreshPipeline', () => controller.refreshPipeline());
  register('gitlabCr.fixPipeline', (arg) => controller.fixPipeline(arg));
  register('gitlabCr.openJob', (arg) => controller.openJob(arg));
  register('gitlabCr.clearPipelineFindings', () => controller.clearPipelineFindings());
  register('gitlabCr.openPipelineView', () => controller.openPipelineView());
  register('gitlabCr.showJobLog', (arg) => controller.showJobLog(arg));
  register('gitlabCr.retryJob', (arg) => controller.jobAction(arg, 'retry'));
  register('gitlabCr.playJob', (arg) => controller.jobAction(arg, 'play'));
  register('gitlabCr.cancelJob', (arg) => controller.jobAction(arg, 'cancel'));

  // Load the notes on startup when a token is already saved. Errors only go to the log here.
  ctx.secrets.get('gitlabCr.token').then((token) => {
    if (token || process.env.GITLAB_TOKEN) {
      controller.refresh().catch((err) => output.appendLine(`Initial refresh failed: ${err?.message ?? err}`));
    }
  });

  return { controller };
}

export function deactivate(): void {}
