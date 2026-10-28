import { CrThread } from './model';
import { CommitFormat } from './text';

const RULES =
  'You are helping a developer handle code review (CR) notes from a GitLab merge request. ' +
  'You may read files in the repository for more context, but you must NOT modify, create or delete any file and must NOT run commands that change the repository.';

const OUTDATED =
  'NOTE: the code at this line was changed after the review note was written. Check if the current code already addresses the note. If it does, return no edits (or say so in the reply).';

function conversation(thread: CrThread): string {
  return thread.notes.map((n) => `--- ${n.author}${n.createdAt ? ' (' + n.createdAt + ')' : ''}:\n${n.body}`).join('\n\n');
}

export function fixPrompt(thread: CrThread, numberedContext: string, contextStart: number, contextEnd: number): string {
  return `${RULES}

Task: propose a code change that addresses the review note below.

File: ${thread.file}
Note is on line: ${thread.line}
${thread.outdated ? OUTDATED + '\n' : ''}
Review conversation:
${conversation(thread)}

Current content of ${thread.file}, lines ${contextStart}-${contextEnd} (format "<line>| <code>"):
${numberedContext}

Answer with ONLY one JSON object in a \`\`\`json fenced block, with this shape:
{
  "explanation": "short description of the change",
  "edits": [
    { "file": "${thread.file}", "startLine": 10, "endLine": 12, "newText": "replacement code" }
  ]
}

Rules for "edits":
- "file" is relative to the repository root. Usually only ${thread.file} needs to change.
- Line numbers are 1-based and refer to the CURRENT file content. "startLine".."endLine" (inclusive) is replaced by "newText".
- "newText" is the full replacement for those lines, with correct indentation, without line-number prefixes and without a trailing newline.
- To insert without replacing, set "endLine" = "startLine" - 1 (the text is inserted before "startLine").
- To delete lines, use "newText": "".
- Keep the edit as small as possible. Edits must not overlap.
- If the note does not need a code change (for example it is a question), return "edits": [] and explain why.`;
}

export function replyPrompt(thread: CrThread, codeContext: string | undefined): string {
  return `${RULES}

Task: write a reply to the review conversation below, as the author of the merge request.
${thread.file ? `\nThe note is on ${thread.location}.` : ''}${thread.outdated ? '\n' + OUTDATED : ''}
${codeContext ? `\nCode around the note:\n${codeContext}\n` : ''}
Review conversation:
${conversation(thread)}

The reply must be short, polite and specific. Answer questions directly. If the reviewer is right, agree and say what you will change. If you disagree, give the technical reason. Use GitLab markdown.

Answer with ONLY one JSON object in a \`\`\`json fenced block: { "reply": "the reply text" }`;
}

export function selfReviewPrompt(baseRef: string, diff: string, truncated: boolean): string {
  return `${RULES}

Task: do a careful code review of the changes below (git diff from ${baseRef} to the working tree), as a senior reviewer would before the merge request is approved.
Look for bugs, wrong logic, edge cases, security problems, race conditions, missing error handling, performance problems, missing tests and unclear code. Do not report pure formatting issues. Report only real issues; it is fine to return no findings.
${truncated ? '\nNOTE: the diff was truncated because it is large. Review what is shown, and read files from the repository if you need more context.\n' : ''}
\`\`\`diff
${diff}
\`\`\`

Answer with ONLY one JSON object in a \`\`\`json fenced block:
{
  "findings": [
    { "file": "path/relative/to/repo", "line": 42, "severity": "high|medium|low", "comment": "what is wrong and how to fix it" }
  ]
}
"line" is the 1-based line number in the NEW version of the file (the "+" side of the diff).`;
}

export function commitPrompt(
  f: CommitFormat,
  diff: string,
  stat: string,
  branch: string,
  recent: string[],
  truncated: boolean,
): string {
  const lines: string[] = [];
  if (f.types.length) {
    lines.push('- "type" must be one of: ' + f.types.map((t) => (t.description ? `${t.name} (${t.description})` : t.name)).join(', ') + '.');
  } else {
    lines.push('- "type": leave empty.');
  }
  if (f.scopes.length) {
    lines.push(`- "scope" must be one of: ${f.scopes.join(', ')}; or empty when none fits.`);
  } else {
    lines.push('- "scope": the part of the code that changed (module, package, component), one or two words, or empty when the change is wide.');
  }
  lines.push(
    `- "subject": one line, at most ${f.subjectMaxLength} characters, imperative mood ("add", not "added" or "adds"), ` +
      (f.subjectCase === 'lower' ? 'first letter in lower case' : f.subjectCase === 'sentence' ? 'first letter in upper case' : 'any case') +
      (f.subjectPeriod ? ', ends with a period.' : ', no period at the end.'),
  );
  if (f.body === 'never') {
    lines.push('- "body": leave empty.');
  } else {
    lines.push(
      `- "body": ${f.body === 'always' ? 'required' : 'only when the subject is not enough'}. Explain WHAT changed and WHY, not how. ` +
        (f.bodyStyle === 'bullets' ? 'Use a bullet list, one change per bullet, each starts with "- ".' : 'Use short paragraphs.') +
        ' No markdown headings or code fences.',
    );
  }
  lines.push('- "breaking": a short description of the breaking change when the change breaks compatibility; otherwise empty.');
  lines.push(`- Write in ${f.language || 'English'}.`);
  if (f.rules.trim()) {
    lines.push(`- Also follow these project rules:\n${f.rules.trim()}`);
  }
  return `You write git commit messages. You may read files in the repository for context, but you must NOT modify, create or delete any file and must NOT run commands that change the repository.

Task: write the commit message for the changes below.
Branch: ${branch}
${recent.length ? `\nRecent commit subjects of this repository (for the style, do not copy them):\n${recent.map((r) => '  ' + r).join('\n')}\n` : ''}${f.examples.length ? `\nExamples of good messages for this project:\n${f.examples.map((e) => '---\n' + e).join('\n')}\n---\n` : ''}
Rules:
${lines.join('\n')}

Changed files:
${stat.trim()}
${truncated ? '\nNOTE: the diff was truncated because it is large. Describe what is shown; read files from the repository when you need more context.\n' : ''}
\`\`\`diff
${diff}
\`\`\`

Answer with ONLY one JSON object in a \`\`\`json fenced block:
{ "type": "", "scope": "", "subject": "", "body": "", "breaking": "" }`;
}

export function pipelinePrompt(
  job: { name: string; stage: string; failureReason?: string },
  log: string,
  truncated: boolean,
  changedFiles: string[],
  baseRef: string,
): string {
  return `${RULES}

Task: a GitLab CI job failed on the merge request of the current branch. Find the cause from the job log and propose a code change that makes the job pass.

Job: "${job.name}" (stage "${job.stage}")${job.failureReason ? `, failure reason reported by GitLab: ${job.failureReason}` : ''}
Files changed on this branch compared to ${baseRef}:
${changedFiles.length ? changedFiles.map((f) => '  ' + f).join('\n') : '  (none)'}

Job log${truncated ? ' (only the end is shown)' : ''}:
\`\`\`
${log}
\`\`\`

First find the first real error in the log (not the follow-up errors). Read the related files in the repository to understand the problem.
Answer with ONLY one JSON object in a \`\`\`json fenced block, with this shape:
{
  "cause": "one or two sentences: what failed and why",
  "explanation": "short description of the proposed change",
  "edits": [
    { "file": "path/relative/to/repo", "startLine": 10, "endLine": 12, "newText": "replacement code" }
  ]
}

Rules for "edits":
- Line numbers are 1-based and refer to the CURRENT file content. "startLine".."endLine" (inclusive) is replaced by "newText".
- "newText" is the full replacement for those lines, with correct indentation, without line-number prefixes and without a trailing newline.
- To insert without replacing, set "endLine" = "startLine" - 1. To delete lines, use "newText": "".
- Keep the edits as small as possible. Edits must not overlap.
- When the failure is not caused by the code (for example a runner problem, a network error, or a missing secret), return "edits": [] and say so in "cause".
- Never make a test pass by removing or skipping it; fix the code it tests.`;
}
