// Pure text helpers with no dependency on the vscode module, so they can be unit tested.

/**
 * Fills a commit message template.
 *  - `{name}` is replaced by the field value (empty when the field is missing).
 *  - `{?name:...}` keeps the text between `:` and the matching `}` only when the field is not empty.
 *    The text can contain other placeholders and conditional blocks.
 *  - `{{` and `}}` give literal braces.
 */
export function renderTemplate(template: string, fields: Record<string, string | undefined>): string {
  const value = (name: string) => (fields[name] ?? '').trim();
  const render = (s: string): string => {
    let out = '';
    for (let i = 0; i < s.length; i++) {
      const ch = s[i];
      if (ch === '{' && s[i + 1] === '{') {
        out += '{';
        i++;
      } else if (ch === '}' && s[i + 1] === '}') {
        out += '}';
        i++;
      } else if (ch === '{' && s[i + 1] === '?') {
        const colon = s.indexOf(':', i + 2);
        const close = matchingBrace(s, i);
        if (colon === -1 || close === -1 || colon > close) {
          out += ch;
          continue;
        }
        const name = s.slice(i + 2, colon).trim();
        if (value(name)) {
          out += render(s.slice(colon + 1, close));
        }
        i = close;
      } else if (ch === '{') {
        const close = s.indexOf('}', i);
        const name = close === -1 ? '' : s.slice(i + 1, close).trim();
        if (close !== -1 && /^[\w.-]+$/.test(name)) {
          out += value(name);
          i = close;
        } else {
          out += ch;
        }
      } else {
        out += ch;
      }
    }
    return out;
  };
  return render(template)
    .replace(/[ \t]+$/gm, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

function matchingBrace(s: string, open: number): number {
  let depth = 0;
  for (let i = open; i < s.length; i++) {
    if (s[i] === '{') {
      depth++;
    } else if (s[i] === '}') {
      depth--;
      if (depth === 0) {
        return i;
      }
    }
  }
  return -1;
}

/** Wraps paragraphs at `width` columns. Bullet lines keep their indent; lines without spaces (URLs) are not cut. */
export function wrapText(text: string, width: number): string {
  if (width <= 0) {
    return text;
  }
  return text
    .split('\n')
    .map((line) => {
      if (line.length <= width) {
        return line;
      }
      const bullet = /^(\s*(?:[-*+]|\d+[.)])\s+)/.exec(line);
      const indent = bullet ? ' '.repeat(bullet[1].length) : (/^\s*/.exec(line) ?? [''])[0];
      const words = line.trim().split(/\s+/);
      const out: string[] = [];
      let current = bullet ? bullet[1] : indent;
      let currentHasWord = false;
      for (const w of words.slice(bullet ? bullet[1].trim().split(/\s+/).length : 0)) {
        if (currentHasWord && current.length + 1 + w.length > width) {
          out.push(current);
          current = indent + w;
        } else {
          current += (currentHasWord ? ' ' : '') + w;
        }
        currentHasWord = true;
      }
      out.push(current);
      return out.join('\n');
    })
    .join('\n');
}

/** Applies the subject rules of the commit format: case of the first letter and no final period. */
export function normalizeSubject(subject: string, subjectCase: 'lower' | 'sentence' | 'any', period: boolean): string {
  let s = subject.trim().replace(/\s+/g, ' ');
  if (subjectCase === 'lower' && /^[A-Z][a-z]/.test(s)) {
    s = s[0].toLowerCase() + s.slice(1);
  } else if (subjectCase === 'sentence' && /^[a-z]/.test(s)) {
    s = s[0].toUpperCase() + s.slice(1);
  }
  if (!period) {
    s = s.replace(/\.+$/, '');
  } else if (!/[.!?]$/.test(s)) {
    s += '.';
  }
  return s;
}

/** Finds an issue reference in a branch name, for example `123-fix-login` -> `123` or `feat/ABC-42` -> `ABC-42`. */
export function extractIssue(branch: string, pattern: string): string {
  if (!pattern) {
    return '';
  }
  try {
    const m = new RegExp(pattern).exec(branch);
    return m ? (m[1] ?? m[0]) : '';
  } catch {
    return '';
  }
}

/** Reads a commit type setting entry such as `feat: a new feature` into its name and description. */
export function parseTypeEntry(entry: string): { name: string; description: string } {
  const i = entry.indexOf(':');
  return i === -1
    ? { name: entry.trim(), description: '' }
    : { name: entry.slice(0, i).trim(), description: entry.slice(i + 1).trim() };
}

/**
 * Removes colors, cursor movement, GitLab section markers and progress-bar rewrites from a CI job log,
 * then keeps only the last `maxBytes` bytes (from the start of a line).
 */
export function cleanJobLog(raw: string, maxBytes: number): { text: string; truncated: boolean } {
  let text = raw
    .replace(/\x1b\[[0-9;?]*[A-Za-z]/g, '')
    .replace(/\x1b\][^\x07]*\x07/g, '')
    .replace(/section_(?:start|end):\d+:[\w.-]+(?:\[[^\]]*\])?\r?/g, '')
    .replace(/\r\n/g, '\n')
    .split('\n')
    .map((line) => {
      // A progress bar rewrites the line with \r; keep what was shown last.
      const parts = line.split('\r');
      return parts[parts.length - 1];
    })
    .join('\n')
    .replace(/\n{3,}/g, '\n\n');
  const bytes = Buffer.byteLength(text);
  if (bytes <= maxBytes) {
    return { text, truncated: false };
  }
  const buf = Buffer.from(text);
  let tail = buf.subarray(bytes - maxBytes).toString('utf8');
  const nl = tail.indexOf('\n');
  if (nl !== -1 && nl < tail.length - 1) {
    tail = tail.slice(nl + 1);
  }
  return { text: tail, truncated: true };
}

export interface CommitFormat {
  types: { name: string; description: string }[];
  scopes: string[];
  subjectMaxLength: number;
  subjectCase: 'lower' | 'sentence' | 'any';
  subjectPeriod: boolean;
  body: 'never' | 'when-useful' | 'always';
  bodyStyle: 'paragraphs' | 'bullets';
  language: string;
  rules: string;
  examples: string[];
}

export interface CommitParts {
  type: string;
  scope: string;
  subject: string;
  body: string;
  breaking: string;
}

/** Checks the model answer against the format. Returns the problems, so the model can be asked again. */
export function checkCommitParts(raw: any, f: CommitFormat): { parts: CommitParts; problems: string[] } {
  const str = (v: unknown) => (typeof v === 'string' ? v.trim() : '');
  const parts: CommitParts = {
    type: str(raw?.type),
    scope: str(raw?.scope),
    subject: normalizeSubject(str(raw?.subject), f.subjectCase, f.subjectPeriod),
    body: str(raw?.body),
    breaking: str(raw?.breaking),
  };
  const problems: string[] = [];
  if (!parts.subject) {
    problems.push('"subject" is empty');
  } else if (parts.subject.length > f.subjectMaxLength) {
    problems.push(`"subject" has ${parts.subject.length} characters, the limit is ${f.subjectMaxLength}`);
  }
  if (f.types.length) {
    const names = f.types.map((t) => t.name);
    const match = names.find((n) => n.toLowerCase() === parts.type.toLowerCase());
    if (match) {
      parts.type = match;
    } else {
      problems.push(`"type" is "${parts.type}", it must be one of ${names.join(', ')}`);
    }
  } else {
    parts.type = '';
  }
  if (f.scopes.length && parts.scope) {
    const match = f.scopes.find((s) => s.toLowerCase() === parts.scope.toLowerCase());
    if (match) {
      parts.scope = match;
    } else {
      problems.push(`"scope" is "${parts.scope}", it must be one of ${f.scopes.join(', ')} or empty`);
    }
  }
  if (f.body === 'never') {
    parts.body = '';
  } else if (f.body === 'always' && !parts.body) {
    problems.push('"body" is empty, it is required');
  }
  return { parts, problems };
}

/** Builds the final message from the parts and the template. */
export function renderCommitMessage(
  parts: CommitParts,
  s: { template: string; footer: string; issuePattern: string; bodyWrap: number },
  context: { branch: string; mr?: number },
): string {
  const issue = extractIssue(context.branch, s.issuePattern);
  const base: Record<string, string> = {
    ...parts,
    body: wrapText(parts.body, s.bodyWrap),
    breaking: wrapText(parts.breaking, s.bodyWrap),
    branch: context.branch,
    issue,
    mr: context.mr ? String(context.mr) : '',
  };
  const footer = renderTemplate(s.footer, base);
  return renderTemplate(s.template, { ...base, footer });
}

