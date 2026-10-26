import { CrThread } from './model';

const RULES =
  'You are helping a developer handle code review (CR) notes from a GitLab merge request. ' +
  'You may read files in the repository for more context, but you must NOT modify, create or delete any file and must NOT run commands that change the repository.';

function conversation(thread: CrThread): string {
  return thread.notes.map((n) => `--- ${n.author}${n.createdAt ? ' (' + n.createdAt + ')' : ''}:\n${n.body}`).join('\n\n');
}

export function fixPrompt(thread: CrThread, numberedContext: string, contextStart: number, contextEnd: number): string {
  return `${RULES}

Task: propose a code change that addresses the review note below.

File: ${thread.file}
Note is on line: ${thread.line}

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
${thread.file ? `\nThe note is on ${thread.location}.` : ''}
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
