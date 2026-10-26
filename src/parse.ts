// Pure helpers with no dependency on the vscode module, so they can be unit tested.

/** Collects assistant text and errors from `opencode run --format json` output (one JSON event per line). */
export function parseOpencodeEvents(stdout: string): { text: string; errors: string[] } {
  const texts: string[] = [];
  const errors: string[] = [];
  for (const line of stdout.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed.startsWith('{')) {
      continue;
    }
    let event: any;
    try {
      event = JSON.parse(trimmed);
    } catch {
      continue;
    }
    if (event.type === 'text' && typeof event.part?.text === 'string') {
      texts.push(event.part.text);
    } else if (event.type === 'error') {
      const e = event.error ?? {};
      errors.push(String(e.data?.message ?? e.message ?? e.name ?? JSON.stringify(e)));
    }
  }
  return { text: texts.join('\n'), errors };
}

/**
 * Finds the JSON value in a model answer. Prefers the last ```json fenced block,
 * then any fenced block, then the first balanced {...} object in the text.
 */
export function extractJson<T = unknown>(text: string): T {
  const fences = [...text.matchAll(/```(json)?\s*\n([\s\S]*?)```/g)];
  const candidates: string[] = [];
  for (const f of fences.filter((m) => m[1]).reverse()) {
    candidates.push(f[2]);
  }
  for (const f of fences.filter((m) => !m[1]).reverse()) {
    candidates.push(f[2]);
  }
  const obj = firstBalancedObject(text);
  if (obj) {
    candidates.push(obj);
  }
  for (const c of candidates) {
    try {
      return JSON.parse(c.trim()) as T;
    } catch {
      // try next candidate
    }
  }
  throw new Error('opencode did not return valid JSON. Answer was:\n' + text.slice(0, 1000));
}

function firstBalancedObject(text: string): string | undefined {
  for (let start = text.indexOf('{'); start !== -1; start = text.indexOf('{', start + 1)) {
    let depth = 0;
    let inString = false;
    let escaped = false;
    for (let i = start; i < text.length; i++) {
      const ch = text[i];
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (ch === '\\') {
          escaped = true;
        } else if (ch === '"') {
          inString = false;
        }
        continue;
      }
      if (ch === '"') {
        inString = true;
      } else if (ch === '{') {
        depth++;
      } else if (ch === '}') {
        depth--;
        if (depth === 0) {
          const candidate = text.slice(start, i + 1);
          try {
            JSON.parse(candidate);
            return candidate;
          } catch {
            break;
          }
        }
      }
    }
  }
  return undefined;
}

/** Prefixes each line with its 1-based number, as context for the model. */
export function numberLines(lines: string[], firstLine: number): string {
  const width = String(firstLine + lines.length - 1).length;
  return lines.map((l, i) => `${String(firstLine + i).padStart(width)}| ${l}`).join('\n');
}

export interface TextEdit {
  file: string;
  startLine: number;
  endLine: number;
  newText: string;
}

export interface FixAnswer {
  explanation: string;
  edits: TextEdit[];
}

export function validateFixAnswer(raw: any): FixAnswer {
  if (!raw || typeof raw !== 'object' || !Array.isArray(raw.edits)) {
    throw new Error('opencode answer has no "edits" array');
  }
  const edits: TextEdit[] = raw.edits.map((e: any, i: number) => {
    const startLine = Number(e.startLine);
    const endLine = Number(e.endLine ?? e.startLine);
    if (typeof e.file !== 'string' || !Number.isInteger(startLine) || !Number.isInteger(endLine)) {
      throw new Error(`Edit ${i + 1} must have "file", "startLine" and "endLine"`);
    }
    if (startLine < 1 || endLine < startLine - 1) {
      throw new Error(`Edit ${i + 1} has an invalid line range ${startLine}-${endLine}`);
    }
    return { file: e.file, startLine, endLine, newText: typeof e.newText === 'string' ? e.newText : '' };
  });
  return { explanation: String(raw.explanation ?? ''), edits };
}

/** Builds a small unified-style diff for display. */
export function renderDiff(oldText: string, newText: string, startLine: number): string {
  const out = [`@@ line ${startLine} @@`];
  for (const l of oldText ? oldText.split('\n') : []) {
    out.push('- ' + l);
  }
  for (const l of newText ? newText.split('\n') : []) {
    out.push('+ ' + l);
  }
  return out.join('\n');
}

/** Ranges are [start, end) offsets. Returns the indexes of items that overlap an earlier accepted item. */
export function findOverlaps(ranges: { start: number; end: number }[]): Set<number> {
  const order = ranges.map((r, i) => ({ ...r, i })).sort((a, b) => a.start - b.start || a.end - b.end);
  const rejected = new Set<number>();
  let lastEnd = -1;
  for (const r of order) {
    if (r.start < lastEnd) {
      rejected.add(r.i);
    } else {
      lastEnd = Math.max(lastEnd, r.end);
    }
  }
  return rejected;
}
