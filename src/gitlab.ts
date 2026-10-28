import * as http from 'http';
import * as https from 'https';

export interface GitLabUser {
  id: number;
  username: string;
  name: string;
  avatar_url?: string;
}

export interface DiffRefs {
  base_sha: string;
  head_sha: string;
  start_sha: string;
}

export interface MergeRequest {
  id: number;
  iid: number;
  title: string;
  web_url: string;
  source_branch: string;
  target_branch: string;
  diff_refs?: DiffRefs;
}

export interface NotePosition {
  position_type: string;
  new_path?: string;
  old_path?: string;
  new_line?: number | null;
  old_line?: number | null;
  head_sha?: string;
}

export interface Note {
  id: number;
  body: string;
  author: GitLabUser;
  created_at: string;
  system: boolean;
  resolvable: boolean;
  resolved?: boolean;
  position?: NotePosition;
}

export interface Discussion {
  id: string;
  individual_note: boolean;
  notes: Note[];
}

export type PipelineStatus =
  | 'created'
  | 'waiting_for_resource'
  | 'preparing'
  | 'pending'
  | 'running'
  | 'success'
  | 'failed'
  | 'canceled'
  | 'skipped'
  | 'manual'
  | 'scheduled';

export interface Pipeline {
  id: number;
  iid?: number;
  status: PipelineStatus;
  ref: string;
  sha: string;
  web_url: string;
  source?: string;
  created_at?: string;
  updated_at?: string;
  finished_at?: string | null;
  duration?: number | null;
  user?: { name: string; username: string };
}

export interface Job {
  id: number;
  name: string;
  stage: string;
  status: PipelineStatus;
  web_url: string;
  allow_failure: boolean;
  failure_reason?: string;
  started_at?: string | null;
  finished_at?: string | null;
  duration?: number | null;
  queued_duration?: number | null;
}

export class GitLabError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message);
  }
}

interface Response {
  status: number;
  headers: http.IncomingHttpHeaders;
  body: string;
}

export class GitLabClient {
  private readonly apiBase: URL;

  constructor(
    baseUrl: string,
    private readonly token: string,
    private readonly strictSSL: boolean,
  ) {
    this.apiBase = new URL(baseUrl.replace(/\/+$/, '') + '/api/v4/');
  }

  private request(method: string, path: string, body?: unknown): Promise<Response> {
    const url = new URL(path.replace(/^\/+/, ''), this.apiBase);
    const payload = body === undefined ? undefined : JSON.stringify(body);
    const lib = url.protocol === 'http:' ? http : https;
    const options: https.RequestOptions = {
      method,
      headers: {
        'PRIVATE-TOKEN': this.token,
        Accept: 'application/json',
        ...(payload ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) } : {}),
      },
      timeout: 30000,
    };
    if (url.protocol === 'https:') {
      options.rejectUnauthorized = this.strictSSL;
    }

    return new Promise((resolve, reject) => {
      const req = lib.request(url, options, (res) => {
        const chunks: Buffer[] = [];
        res.on('data', (c: Buffer) => chunks.push(c));
        res.on('end', () =>
          resolve({ status: res.statusCode ?? 0, headers: res.headers, body: Buffer.concat(chunks).toString('utf8') }),
        );
      });
      req.on('timeout', () => req.destroy(new Error(`Request to ${url.host} timed out`)));
      req.on('error', (err) => reject(new GitLabError(`GitLab request failed: ${err.message}`)));
      if (payload) {
        req.write(payload);
      }
      req.end();
    });
  }

  private async json<T>(method: string, path: string, body?: unknown): Promise<T> {
    const res = await this.request(method, path, body);
    if (res.status < 200 || res.status >= 300) {
      let detail = res.body;
      try {
        const parsed = JSON.parse(res.body);
        detail = parsed.message ? JSON.stringify(parsed.message) : parsed.error ?? res.body;
      } catch {
        // keep raw body
      }
      throw new GitLabError(`GitLab ${method} ${path} returned ${res.status}: ${String(detail).slice(0, 300)}`, res.status);
    }
    return JSON.parse(res.body) as T;
  }

  private async paginate<T>(path: string): Promise<T[]> {
    const all: T[] = [];
    let page = '1';
    while (page) {
      const sep = path.includes('?') ? '&' : '?';
      const res = await this.request('GET', `${path}${sep}per_page=100&page=${page}`);
      if (res.status < 200 || res.status >= 300) {
        throw new GitLabError(`GitLab GET ${path} returned ${res.status}: ${res.body.slice(0, 300)}`, res.status);
      }
      all.push(...(JSON.parse(res.body) as T[]));
      const next = res.headers['x-next-page'];
      page = typeof next === 'string' ? next : '';
    }
    return all;
  }

  private static project(project: string): string {
    return encodeURIComponent(project);
  }

  currentUser(): Promise<GitLabUser> {
    return this.json('GET', 'user');
  }

  async findMergeRequest(project: string, sourceBranch: string): Promise<MergeRequest | undefined> {
    const p = GitLabClient.project(project);
    const list = await this.json<MergeRequest[]>(
      'GET',
      `projects/${p}/merge_requests?state=opened&source_branch=${encodeURIComponent(sourceBranch)}`,
    );
    if (!list.length) {
      return undefined;
    }
    // The list endpoint does not include diff_refs.
    return this.json<MergeRequest>('GET', `projects/${p}/merge_requests/${list[0].iid}`);
  }

  discussions(project: string, iid: number): Promise<Discussion[]> {
    return this.paginate(`projects/${GitLabClient.project(project)}/merge_requests/${iid}/discussions`);
  }

  addNote(project: string, iid: number, discussionId: string, body: string): Promise<Note> {
    return this.json(
      'POST',
      `projects/${GitLabClient.project(project)}/merge_requests/${iid}/discussions/${discussionId}/notes`,
      { body },
    );
  }

  setResolved(project: string, iid: number, discussionId: string, resolved: boolean): Promise<Discussion> {
    return this.json(
      'PUT',
      `projects/${GitLabClient.project(project)}/merge_requests/${iid}/discussions/${discussionId}?resolved=${resolved}`,
    );
  }

  /** Pipelines of the merge request, newest first. */
  mergeRequestPipelines(project: string, iid: number): Promise<Pipeline[]> {
    return this.json('GET', `projects/${GitLabClient.project(project)}/merge_requests/${iid}/pipelines`);
  }

  pipelineJobs(project: string, pipelineId: number): Promise<Job[]> {
    return this.paginate(`projects/${GitLabClient.project(project)}/pipelines/${pipelineId}/jobs?include_retried=false`);
  }

  /** Full details of a pipeline (the list endpoint has no duration). */
  pipeline(project: string, pipelineId: number): Promise<Pipeline> {
    return this.json('GET', `projects/${GitLabClient.project(project)}/pipelines/${pipelineId}`);
  }

  retryJob(project: string, jobId: number): Promise<Job> {
    return this.json('POST', `projects/${GitLabClient.project(project)}/jobs/${jobId}/retry`);
  }

  playJob(project: string, jobId: number): Promise<Job> {
    return this.json('POST', `projects/${GitLabClient.project(project)}/jobs/${jobId}/play`);
  }

  cancelJob(project: string, jobId: number): Promise<Job> {
    return this.json('POST', `projects/${GitLabClient.project(project)}/jobs/${jobId}/cancel`);
  }

  retryPipeline(project: string, pipelineId: number): Promise<Pipeline> {
    return this.json('POST', `projects/${GitLabClient.project(project)}/pipelines/${pipelineId}/retry`);
  }

  cancelPipeline(project: string, pipelineId: number): Promise<Pipeline> {
    return this.json('POST', `projects/${GitLabClient.project(project)}/pipelines/${pipelineId}/cancel`);
  }

  /** Raw log of a job (plain text). */
  async jobTrace(project: string, jobId: number): Promise<string> {
    const res = await this.request('GET', `projects/${GitLabClient.project(project)}/jobs/${jobId}/trace`);
    if (res.status < 200 || res.status >= 300) {
      throw new GitLabError(`GitLab GET jobs/${jobId}/trace returned ${res.status}: ${res.body.slice(0, 300)}`, res.status);
    }
    return res.body;
  }

  createDiscussion(
    project: string,
    iid: number,
    body: string,
    position?: Record<string, unknown>,
  ): Promise<Discussion> {
    return this.json('POST', `projects/${GitLabClient.project(project)}/merge_requests/${iid}/discussions`, {
      body,
      ...(position ? { position } : {}),
    });
  }
}
