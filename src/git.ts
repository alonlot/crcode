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

export async function gitDir(root: string): Promise<string> {
  return (await git(root, ['rev-parse', '--absolute-git-dir'])).trim();
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

export async function hasCommit(root: string, sha: string): Promise<boolean> {
  try {
    await git(root, ['cat-file', '-e', `${sha}^{commit}`]);
    return true;
  } catch {
    return false;
  }
}

/** Fetches a branch into the remote-tracking refs only; it does not change local branches or files. */
export async function fetchBranch(root: string, remote: string, branch: string): Promise<void> {
  await git(root, ['fetch', '--quiet', '--no-tags', remote, branch]);
}

/** Diff from a commit to the working tree for one file, with no context lines. */
export function diffFileSince(root: string, sha: string, file: string): Promise<string> {
  return git(root, ['diff', '--no-color', '--unified=0', sha, '--', file]);
}

/** Staged changes, or when nothing is staged, all changes of tracked files. */
export async function changesForCommit(root: string, source: 'auto' | 'staged' | 'all'): Promise<{ diff: string; staged: boolean }> {
  if (source !== 'all') {
    const staged = await git(root, ['diff', '--no-color', '--cached', '--unified=3', '--']);
    if (staged.trim() || source === 'staged') {
      return { diff: staged, staged: true };
    }
  }
  return { diff: await git(root, ['diff', '--no-color', '--unified=3', 'HEAD', '--']), staged: false };
}

export function diffStat(root: string, staged: boolean): Promise<string> {
  return git(root, staged ? ['diff', '--cached', '--stat=100', '--'] : ['diff', '--stat=100', 'HEAD', '--']);
}

export async function recentSubjects(root: string, count: number): Promise<string[]> {
  if (count <= 0) {
    return [];
  }
  try {
    const out = await git(root, ['log', `-${count}`, '--format=%s']);
    return out.split('\n').filter((l) => l.trim());
  } catch {
    return []; // for example a repository without commits
  }
}

/** Files changed between a commit and the working tree. */
export async function changedFiles(root: string, base: string): Promise<string[]> {
  const out = await git(root, ['diff', '--name-only', base, '--']);
  return out.split('\n').filter((l) => l.trim());
}
