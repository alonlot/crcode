import assert from 'node:assert/strict';
import { test } from 'node:test';
import { CiJob, formatDuration, groupStages, jobActions, shownStatus, stageStatus } from '../ciModel';

const job = (id: number, stage: string, status: CiJob['status'], allow = false): CiJob => ({
  id,
  name: `j${id}`,
  stage,
  status,
  allow_failure: allow,
  web_url: '',
});

test('groupStages keeps the stage order and computes the stage status', () => {
  const stages = groupStages([
    job(1, 'build', 'success'),
    job(2, 'test', 'failed', true),
    job(3, 'test', 'success'),
    job(4, 'deploy', 'manual'),
    job(5, 'build', 'success'),
  ]);
  assert.deepEqual(
    stages.map((s) => [s.name, s.status, s.jobs.length]),
    [
      ['build', 'success', 2],
      ['test', 'warning', 2],
      ['deploy', 'manual', 1],
    ],
  );
});

test('stageStatus follows the GitLab rules', () => {
  assert.equal(stageStatus([job(1, 's', 'success'), job(2, 's', 'running')]), 'running');
  assert.equal(stageStatus([job(1, 's', 'failed'), job(2, 's', 'pending')]), 'pending');
  assert.equal(stageStatus([job(1, 's', 'failed'), job(2, 's', 'success')]), 'failed');
  assert.equal(stageStatus([job(1, 's', 'canceled'), job(2, 's', 'success')]), 'canceled');
  assert.equal(stageStatus([job(1, 's', 'skipped'), job(2, 's', 'skipped')]), 'skipped');
  assert.equal(stageStatus([job(1, 's', 'skipped'), job(2, 's', 'success')]), 'success');
  assert.equal(shownStatus(job(1, 's', 'failed', true)), 'warning');
});

test('formatDuration and jobActions', () => {
  assert.equal(formatDuration(42.4), '42s');
  assert.equal(formatDuration(125), '2m 5s');
  assert.equal(formatDuration(3700), '1h 1m');
  assert.equal(formatDuration(null), '');
  assert.deepEqual(jobActions({ status: 'failed' }), ['retry']);
  assert.deepEqual(jobActions({ status: 'manual' }), ['play']);
  assert.deepEqual(jobActions({ status: 'running' }), ['cancel']);
  assert.deepEqual(jobActions({ status: 'skipped' }), []);
});
