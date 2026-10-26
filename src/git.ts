import { execFile } from 'child_process';

export function git(cwd: string, args: string[]): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile('git', args, { cwd, maxBuffer: 64 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) {
        reject(new Error(`git ${args.join(' ')} failed: ${stderr.trim() || err.message}`));
      } else {
        resolve(stdout);
      }
    });
  });
}

export async function repoRoot(cwd: string): Promise<string> {
  return (await git(cwd, ['rev-parse', '--show-toplevel'])).trim();
}

export async function currentBranch(root: string): Promise<string> {
  return (await git(root, ['rev-parse', '--abbrev-ref', 'HEAD'])).trim();
}

export async function remoteUrl(root: string, remote: string): Promise<string> {
  return (await git(root, ['remote', 'get-url', remote])).trim();
}

export interface ParsedRemote {
  host: string;
  /** Full path on the server, without leading slash and without `.git`. */
  path: string;
  /** https base URL guessed from the remote. */
  guessedBaseUrl: string;
}

/**
 * Parses remotes such as:
 *   git@gitlab.example.com:group/sub/project.git
 *   ssh://git@gitlab.example.com:2222/group/project.git
 *   https://gitlab.example.com/group/project.git
 *   https://user:token@gitlab.example.com/gitlab/group/project
 */
export function parseRemote(url: string): ParsedRemote | undefined {
  const scp = /^(?:[^@/]+@)?([^:/]+):(?!\/)(.+?)(?:\.git)?\/?$/.exec(url);
  if (scp && !/^[a-z][a-z0-9+.-]*:\/\//i.test(url)) {
    return { host: scp[1], path: scp[2], guessedBaseUrl: `https://${scp[1]}` };
  }
  try {
    const u = new URL(url);
    const path = u.pathname.replace(/^\/+/, '').replace(/\/+$/, '').replace(/\.git$/, '');
    if (!path) {
      return undefined;
    }
    const scheme = u.protocol === 'http:' ? 'http' : 'https';
    const base = u.protocol === 'http:' || u.protocol === 'https:' ? `${scheme}://${u.host}` : `https://${u.hostname}`;
    return { host: u.hostname, path, guessedBaseUrl: base };
  } catch {
    return undefined;
  }
}

/** Removes a relative URL root (for example `/gitlab`) of the base URL from a project path. */
export function stripBasePath(projectPath: string, baseUrl: string): string {
  try {
    const prefix = new URL(baseUrl).pathname.replace(/^\/+|\/+$/g, '');
    if (prefix && projectPath.startsWith(prefix + '/')) {
      return projectPath.slice(prefix.length + 1);
    }
  } catch {
    // invalid base URL, keep the path
  }
  return projectPath;
}

export async function mergeBase(root: string, baseBranch: string, remote: string): Promise<string> {
  for (const ref of [`${remote}/${baseBranch}`, baseBranch]) {
    try {
      return (await git(root, ['merge-base', 'HEAD', ref])).trim();
    } catch {
      // try next ref
    }
  }
  throw new Error(`Cannot find branch '${baseBranch}' (also tried '${remote}/${baseBranch}'). Fetch it first.`);
}

/** Diff between the merge base and the working tree, so uncommitted changes are included. */
export function diffSince(root: string, base: string): Promise<string> {
  return git(root, ['diff', '--no-color', '--unified=3', base, '--']);
}
