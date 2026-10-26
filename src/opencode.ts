import { spawn } from 'child_process';
import * as vscode from 'vscode';
import { extractJson, parseOpencodeEvents } from './parse';

export class OpencodeRunner {
  constructor(private readonly output: vscode.OutputChannel) {}

  /** Sends the prompt to `opencode run` and returns the parsed JSON answer. */
  async askJson<T>(cwd: string, prompt: string, token?: vscode.CancellationToken): Promise<T> {
    const text = await this.ask(cwd, prompt, token);
    return extractJson<T>(text);
  }

  ask(cwd: string, prompt: string, token?: vscode.CancellationToken): Promise<string> {
    const cfg = vscode.workspace.getConfiguration('gitlabCr.opencode');
    const exe = cfg.get<string>('path') || 'opencode';
    const model = cfg.get<string>('model');
    const agent = cfg.get<string>('agent');
    const timeoutMs = Math.max(10, cfg.get<number>('timeoutSeconds') ?? 300) * 1000;

    // The prompt goes through stdin (opencode appends it to the message), so long diffs do not hit argv limits.
    const args = ['run', '--format', 'json'];
    if (model) {
      args.push('--model', model);
    }
    if (agent) {
      args.push('--agent', agent);
    }
    args.push('Do the task described in this message.');

    this.output.appendLine(`\n$ ${exe} ${args.join(' ')}   (cwd: ${cwd}, prompt: ${prompt.length} chars)`);

    return new Promise<string>((resolve, reject) => {
      const child = spawn(exe, args, { cwd, env: process.env, stdio: ['pipe', 'pipe', 'pipe'] });
      let stdout = '';
      let stderr = '';
      let settled = false;
      const finish = (fn: () => void) => {
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          sub?.dispose();
          fn();
        }
      };
      const timer = setTimeout(() => {
        child.kill();
        finish(() => reject(new Error(`opencode did not answer in ${timeoutMs / 1000} s`)));
      }, timeoutMs);
      const sub = token?.onCancellationRequested(() => {
        child.kill();
        finish(() => reject(new vscode.CancellationError()));
      });

      child.stdout.on('data', (d: Buffer) => (stdout += d.toString('utf8')));
      child.stderr.on('data', (d: Buffer) => {
        stderr += d.toString('utf8');
        this.output.append(d.toString('utf8'));
      });
      child.on('error', (err: NodeJS.ErrnoException) => {
        const msg =
          err.code === 'ENOENT'
            ? `Cannot find '${exe}'. Install opencode or set "gitlabCr.opencode.path".`
            : `Cannot start opencode: ${err.message}`;
        finish(() => reject(new Error(msg)));
      });
      child.on('close', (code) => {
        const { text, errors } = parseOpencodeEvents(stdout);
        this.output.appendLine(text || '(no text output)');
        finish(() => {
          if (errors.length) {
            reject(new Error('opencode error: ' + errors.join('; ')));
          } else if (!text.trim()) {
            reject(new Error(`opencode returned no answer (exit code ${code}). ${stderr.trim().slice(-500)}`));
          } else {
            resolve(text);
          }
        });
      });

      child.stdin.on('error', () => {
        // the process can exit before it reads stdin; the close handler reports it
      });
      child.stdin.end(prompt);
    });
  }
}
