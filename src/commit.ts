import * as vscode from 'vscode';
import * as git from './git';
import { OpencodeRunner } from './opencode';
import { commitPrompt } from './prompts';
import { CommitFormat } from './text';
import { checkCommitParts, parseTypeEntry, renderCommitMessage } from './text';

export interface CommitSettings extends CommitFormat {
  template: string;
  footer: string;
  issuePattern: string;
  bodyWrap: number;
  source: 'auto' | 'staged' | 'all';
  maxDiffKb: number;
  recentCommits: number;
}

export function readCommitSettings(): CommitSettings {
  const cfg = vscode.workspace.getConfiguration('gitlabCr.commit');
  return {
    template: cfg.get<string>('template') ?? '',
    types: (cfg.get<string[]>('types') ?? []).map(parseTypeEntry).filter((t) => t.name),
    scopes: (cfg.get<string[]>('scopes') ?? []).map((s) => s.trim()).filter(Boolean),
    subjectMaxLength: cfg.get<number>('subjectMaxLength') ?? 72,
    subjectCase: cfg.get<'lower' | 'sentence' | 'any'>('subjectCase') ?? 'lower',
    subjectPeriod: cfg.get<boolean>('subjectPeriod') ?? false,
    body: cfg.get<'never' | 'when-useful' | 'always'>('body') ?? 'when-useful',
    bodyStyle: cfg.get<'paragraphs' | 'bullets'>('bodyStyle') ?? 'paragraphs',
    bodyWrap: cfg.get<number>('bodyWrap') ?? 72,
    footer: cfg.get<string>('footer') ?? '',
    issuePattern: cfg.get<string>('issuePattern') ?? '',
    language: cfg.get<string>('language') ?? 'English',
    rules: cfg.get<string>('rules') ?? '',
    examples: cfg.get<string[]>('examples') ?? [],
    source: cfg.get<'auto' | 'staged' | 'all'>('source') ?? 'auto',
    maxDiffKb: cfg.get<number>('maxDiffKb') ?? 200,
    recentCommits: cfg.get<number>('recentCommits') ?? 5,
  };
}

export class CommitWriter {
  constructor(
    private readonly opencode: OpencodeRunner,
    private readonly output: vscode.OutputChannel,
  ) {}

  /** Asks opencode for the message of the current changes and puts it in the Source Control input box. */
  async write(root: string, mr?: number): Promise<string> {
    const s = readCommitSettings();
    const { diff, staged } = await git.changesForCommit(root, s.source);
    if (!diff.trim()) {
      throw new Error(
        s.source === 'staged' ? 'There are no staged changes.' : 'There are no changes to commit (new files must be added with git add first).',
      );
    }
    const maxBytes = Math.max(10, s.maxDiffKb) * 1024;
    const truncated = Buffer.byteLength(diff) > maxBytes;
    const shownDiff = truncated ? Buffer.from(diff).subarray(0, maxBytes).toString('utf8') : diff;
    const [stat, branch, recent] = await Promise.all([
      git.diffStat(root, staged),
      git.currentBranch(root),
      git.recentSubjects(root, s.recentCommits),
    ]);

    const message = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'opencode: writing the commit message', cancellable: true },
      async (_p, token) => {
        let prompt = commitPrompt(s, shownDiff, stat, branch, recent, truncated);
        let result = checkCommitParts(await this.opencode.askJson(root, prompt, token), s);
        if (result.problems.length) {
          // One more try with the problems listed; after that the best answer is used.
          this.output.appendLine('Commit message does not follow the format, asking again: ' + result.problems.join('; '));
          prompt += `\n\nYour previous answer had these problems, fix them:\n- ${result.problems.join('\n- ')}`;
          const second = checkCommitParts(await this.opencode.askJson(root, prompt, token), s);
          if (second.problems.length < result.problems.length || !second.problems.length) {
            result = second;
          }
          if (result.problems.length) {
            vscode.window.showWarningMessage('The commit message does not fully follow the format: ' + result.problems.join('; '));
          }
        }
        return renderCommitMessage(result.parts, s, { branch, mr });
      },
    );
    await this.show(root, message, staged);
    return message;
  }

  private async show(root: string, message: string, staged: boolean): Promise<void> {
    const box = scmInputBox(root);
    if (box) {
      box.value = message;
      await vscode.commands.executeCommand('workbench.view.scm');
      vscode.window.setStatusBarMessage(
        `$(git-commit) Commit message written to the Source Control box${staged ? '' : ' (nothing is staged: it describes all changes)'}.`,
        8000,
      );
      return;
    }
    const doc = await vscode.workspace.openTextDocument({ language: 'git-commit', content: message });
    await vscode.window.showTextDocument(doc, { preview: false });
    vscode.window.showInformationMessage('The built-in Git extension is not active. The commit message is in the new editor.');
  }
}

interface GitRepository {
  rootUri: vscode.Uri;
  inputBox: { value: string };
}

function scmInputBox(root: string): { value: string } | undefined {
  try {
    const ext = vscode.extensions.getExtension<{ getAPI(v: number): { repositories: GitRepository[] } }>('vscode.git');
    if (!ext?.isActive) {
      return undefined;
    }
    const repos = ext.exports.getAPI(1).repositories;
    const norm = (p: string) => p.replace(/[\\/]+$/, '').toLowerCase();
    const repo = repos.find((r) => norm(r.rootUri.fsPath) === norm(root)) ?? repos[0];
    return repo?.inputBox;
  } catch {
    return undefined;
  }
}
