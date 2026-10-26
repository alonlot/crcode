import * as assert from 'assert';
import { test } from 'node:test';
import { parseRemote, stripBasePath } from '../git';
import { extractJson, findOverlaps, numberLines, parseOpencodeEvents, validateFixAnswer } from '../parse';

test('parseRemote handles scp, ssh and https remotes', () => {
  assert.deepStrictEqual(parseRemote('git@gitlab.corp.local:group/sub/proj.git'), {
    host: 'gitlab.corp.local',
    path: 'group/sub/proj',
    guessedBaseUrl: 'https://gitlab.corp.local',
  });
  assert.strictEqual(parseRemote('ssh://git@gitlab.corp.local:2222/group/proj.git')?.path, 'group/proj');
  assert.strictEqual(parseRemote('ssh://git@gitlab.corp.local:2222/group/proj.git')?.guessedBaseUrl, 'https://gitlab.corp.local');
  const http = parseRemote('http://user:tok@10.0.0.5:8080/gitlab/group/proj');
  assert.strictEqual(http?.path, 'gitlab/group/proj');
  assert.strictEqual(http?.guessedBaseUrl, 'http://10.0.0.5:8080');
  assert.strictEqual(stripBasePath('gitlab/group/proj', 'http://10.0.0.5:8080/gitlab'), 'group/proj');
  assert.strictEqual(stripBasePath('group/proj', 'https://gitlab.corp.local'), 'group/proj');
});

test('parseOpencodeEvents collects text and errors', () => {
  const out = [
    JSON.stringify({ type: 'step_start', part: {} }),
    JSON.stringify({ type: 'text', part: { type: 'text', text: 'hello' } }),
    'not json',
    JSON.stringify({ type: 'tool_use', part: { tool: 'read' } }),
    JSON.stringify({ type: 'text', part: { type: 'text', text: '```json\n{"a":1}\n```' } }),
    JSON.stringify({ type: 'error', error: { name: 'APIError', data: { message: 'rate limited' } } }),
  ].join('\n');
  const r = parseOpencodeEvents(out);
  assert.strictEqual(r.text, 'hello\n```json\n{"a":1}\n```');
  assert.deepStrictEqual(r.errors, ['rate limited']);
});

test('extractJson prefers the last json fence and falls back to a bare object', () => {
  assert.deepStrictEqual(extractJson('x\n```json\n{"a":1}\n```\nthen\n```json\n{"a":2}\n```'), { a: 2 });
  assert.deepStrictEqual(extractJson('Sure! {"reply": "use {braces} \\"ok\\""} done'), { reply: 'use {braces} "ok"' });
  assert.throws(() => extractJson('no json here'));
});

test('validateFixAnswer checks the edits', () => {
  const ok = validateFixAnswer({ explanation: 'e', edits: [{ file: 'a.ts', startLine: 3, endLine: 4, newText: 'x' }] });
  assert.strictEqual(ok.edits[0].endLine, 4);
  const insert = validateFixAnswer({ edits: [{ file: 'a.ts', startLine: 3, endLine: 2, newText: 'x' }] });
  assert.strictEqual(insert.edits[0].endLine, 2);
  assert.throws(() => validateFixAnswer({ edits: [{ file: 'a.ts', startLine: 5, endLine: 2 }] }));
  assert.throws(() => validateFixAnswer({ explanation: 'no edits' }));
});

test('findOverlaps rejects later overlapping ranges only', () => {
  const rejected = findOverlaps([
    { start: 0, end: 10 },
    { start: 20, end: 30 },
    { start: 5, end: 8 },
    { start: 10, end: 10 },
  ]);
  assert.deepStrictEqual([...rejected], [2]);
});

test('numberLines pads line numbers', () => {
  assert.strictEqual(numberLines(['a', 'b'], 9), ' 9| a\n10| b');
});
