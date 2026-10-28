import assert from 'node:assert/strict';
import { test } from 'node:test';
import { checkCommitParts, cleanJobLog, extractIssue, normalizeSubject, parseTypeEntry, renderCommitMessage, renderTemplate, wrapText } from '../text';

const DEFAULT_TEMPLATE =
  '{?type:{type}{?scope:({scope})}{?breaking:!}: }{subject}\n\n{?body:{body}\n\n}{?breaking:BREAKING CHANGE: {breaking}\n\n}{?footer:{footer}}';

test('renderTemplate: placeholders, conditional blocks, literal braces', () => {
  const all = { type: 'feat', scope: 'api', subject: 'add login', body: 'Why.', breaking: 'token format', footer: 'Refs: #12' };
  assert.equal(renderTemplate(DEFAULT_TEMPLATE, all), 'feat(api)!: add login\n\nWhy.\n\nBREAKING CHANGE: token format\n\nRefs: #12');
  assert.equal(renderTemplate(DEFAULT_TEMPLATE, { type: 'fix', subject: 'typo' }), 'fix: typo');
  assert.equal(renderTemplate(DEFAULT_TEMPLATE, { subject: 'no type' }), 'no type');
  assert.equal(renderTemplate('{subject} {{json}} {unknown}', { subject: 's' }), 's {json}');
  assert.equal(renderTemplate('[{?issue:#{issue} }{subject}]', { issue: '7', subject: 's' }), '[#7 s]');
  assert.equal(renderTemplate('{?a:{?b:both}{?c:ac}}', { a: '1', c: '1' }), 'ac');
  assert.equal(renderTemplate('a\n\n\n\nb  \n', {}), 'a\n\nb');
});

test('wrapText wraps paragraphs and bullets, keeps long words', () => {
  assert.equal(wrapText('one two three four five', 9), 'one two\nthree\nfour five');
  assert.equal(wrapText('- one two three four', 10), '- one two\n  three\n  four');
  assert.equal(wrapText('https://example.com/a/very/long/url/that/has/no/spaces', 10), 'https://example.com/a/very/long/url/that/has/no/spaces');
  assert.equal(wrapText('short\n\nalso short', 72), 'short\n\nalso short');
  assert.equal(wrapText('no wrap at all', 0), 'no wrap at all');
});

test('normalizeSubject, extractIssue, parseTypeEntry', () => {
  assert.equal(normalizeSubject('Add  login.', 'lower', false), 'add login');
  assert.equal(normalizeSubject('add login', 'sentence', true), 'Add login.');
  assert.equal(normalizeSubject('JSON parser fix', 'lower', false), 'JSON parser fix'); // an acronym stays
  const pattern = '(?:^|[/_-])([A-Z]+-\\d+|\\d+)(?=[/_-]|$)';
  assert.equal(extractIssue('123-fix-login', pattern), '123');
  assert.equal(extractIssue('feature/ABC-42-login', pattern), 'ABC-42');
  assert.equal(extractIssue('main', pattern), '');
  assert.equal(extractIssue('x', '('), '');
  assert.deepEqual(parseTypeEntry('feat: a new feature'), { name: 'feat', description: 'a new feature' });
  assert.deepEqual(parseTypeEntry(' chore '), { name: 'chore', description: '' });
});

const format = {
  types: [{ name: 'feat', description: '' }, { name: 'fix', description: '' }],
  scopes: ['api', 'ui'],
  subjectMaxLength: 20,
  subjectCase: 'lower' as const,
  subjectPeriod: false,
  body: 'when-useful' as const,
  bodyStyle: 'paragraphs' as const,
  language: 'English',
  rules: '',
  examples: [],
};

test('checkCommitParts reports format problems and normalizes', () => {
  const ok = checkCommitParts({ type: 'Fix', scope: 'API', subject: 'Handle null.', body: 'b' }, format);
  assert.deepEqual(ok.problems, []);
  assert.deepEqual(ok.parts, { type: 'fix', scope: 'api', subject: 'handle null', body: 'b', breaking: '' });
  const bad = checkCommitParts({ type: 'chore', scope: 'db', subject: 'a very long subject that is too long' }, format);
  assert.equal(bad.problems.length, 3);
  const noBody = checkCommitParts({ type: 'fix', subject: 's', body: 'x' }, { ...format, body: 'never' });
  assert.equal(noBody.parts.body, '');
  const noTypes = checkCommitParts({ type: 'fix', subject: 's' }, { ...format, types: [] });
  assert.equal(noTypes.parts.type, '');
  assert.deepEqual(noTypes.problems, []);
});

test('renderCommitMessage fills the template and the footer', () => {
  const msg = renderCommitMessage(
    { type: 'fix', scope: '', subject: 'handle null', body: 'one two three four five six', breaking: '' },
    { template: DEFAULT_TEMPLATE, footer: '{?issue:Refs: #{issue}}{?mr: !{mr}}', issuePattern: '(\\d+)', bodyWrap: 12 },
    { branch: '42-null', mr: 7 },
  );
  assert.equal(msg, 'fix: handle null\n\none two\nthree four\nfive six\n\nRefs: #42 !7');
});

test('cleanJobLog strips ANSI, sections and progress rewrites, keeps the tail', () => {
  const raw = '\x1b[0KRunning with gitlab-runner\r\n\x1b[0Ksection_start:1700000000:step_script\r\x1b[0K\x1b[32;1m$ npm test\x1b[0;m\r\nprogress 10%\rprogress 100%\r\nFAIL src/a.test.ts\nsection_end:1700000001:step_script\r\x1b[0K\n';
  const out = cleanJobLog(raw, 10_000);
  assert.equal(out.truncated, false);
  assert.equal(out.text, 'Running with gitlab-runner\n$ npm test\nprogress 100%\nFAIL src/a.test.ts\n\n');
  const tail = cleanJobLog('line1\nline2\nline3\n', 9);
  assert.equal(tail.truncated, true);
  assert.equal(tail.text, 'line3\n');
});
