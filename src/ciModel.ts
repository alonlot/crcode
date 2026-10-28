// Pure helpers for the pipeline graph, with no dependency on the vscode module.

export type CiStatus =
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

export interface CiJob {
  id: number;
  name: string;
  stage: string;
  status: CiStatus;
  allow_failure: boolean;
  duration?: number | null;
  queued_duration?: number | null;
  failure_reason?: string;
  web_url: string;
}

/** The status shown for a job: a failed job that is allowed to fail shows as a warning, as on GitLab. */
export type ShownStatus = CiStatus | 'warning';

export function shownStatus(job: Pick<CiJob, 'status' | 'allow_failure'>): ShownStatus {
  return job.status === 'failed' && job.allow_failure ? 'warning' : job.status;
}

export interface CiStage {
  name: string;
  status: ShownStatus;
  jobs: CiJob[];
}

/** Groups jobs by stage in the order the stages appear, and computes the stage status like GitLab. */
export function groupStages(jobs: CiJob[]): CiStage[] {
  const stages: CiStage[] = [];
  for (const j of jobs) {
    let s = stages.find((x) => x.name === j.stage);
    if (!s) {
      s = { name: j.stage, status: 'created', jobs: [] };
      stages.push(s);
    }
    s.jobs.push(j);
  }
  for (const s of stages) {
    s.status = stageStatus(s.jobs);
  }
  return stages;
}

export function stageStatus(jobs: CiJob[]): ShownStatus {
  const has = (...st: CiStatus[]) => jobs.some((j) => st.includes(j.status));
  if (has('running')) {
    return 'running';
  }
  if (has('pending', 'created', 'waiting_for_resource', 'preparing')) {
    return 'pending';
  }
  if (jobs.some((j) => j.status === 'failed' && !j.allow_failure)) {
    return 'failed';
  }
  if (has('canceled')) {
    return 'canceled';
  }
  if (has('manual')) {
    return 'manual';
  }
  if (has('scheduled')) {
    return 'scheduled';
  }
  if (jobs.some((j) => j.status === 'failed' && j.allow_failure)) {
    return 'warning';
  }
  if (jobs.length && jobs.every((j) => j.status === 'skipped')) {
    return 'skipped';
  }
  return 'success';
}

export function statusLabel(status: ShownStatus): string {
  switch (status) {
    case 'warning':
      return 'failed (allowed to fail)';
    case 'waiting_for_resource':
      return 'waiting for resource';
    default:
      return status;
  }
}

export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds)) {
    return '';
  }
  const s = Math.round(seconds);
  if (s < 60) {
    return `${s}s`;
  }
  const m = Math.floor(s / 60);
  if (m < 60) {
    return `${m}m ${s % 60}s`;
  }
  return `${Math.floor(m / 60)}h ${m % 60}m`;
}

/** Which actions GitLab offers for a job in this status. */
export function jobActions(job: Pick<CiJob, 'status'>): ('retry' | 'play' | 'cancel')[] {
  switch (job.status) {
    case 'failed':
    case 'canceled':
    case 'success':
      return ['retry'];
    case 'manual':
      return ['play'];
    case 'running':
    case 'pending':
      return ['cancel'];
    default:
      return [];
  }
}
