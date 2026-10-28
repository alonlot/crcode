import * as path from 'path';
import * as vscode from 'vscode';
import { PreparedEdit } from './model';
import { TextEdit, findOverlaps } from './parse';

export function resolveInRoot(root: string, rel: string): string {
  const abs = path.resolve(root, rel);
  const relToRoot = path.relative(root, abs);
  if (!relToRoot || relToRoot.startsWith('..') || path.isAbsolute(relToRoot)) {
    throw new Error(`Refusing to edit '${rel}': it is outside the repository`);
  }
  return abs;
}

function eolOf(doc: vscode.TextDocument): string {
  return doc.eol === vscode.EndOfLine.CRLF ? '\r\n' : '\n';
}

/** Turns a line-based edit from opencode into an exact region of the current document. */
export function prepareEdit(doc: vscode.TextDocument, edit: TextEdit): PreparedEdit {
  const eol = eolOf(doc);
  const newText = edit.newText.replace(/\r?\n/g, eol);
  const lineCount = doc.lineCount;

  if (edit.endLine === edit.startLine - 1) {
    // Pure insert before startLine.
    if (edit.startLine > lineCount + 1) {
      throw new Error(`${edit.file}: cannot insert at line ${edit.startLine}, the file has ${lineCount} lines`);
    }
    if (edit.startLine === lineCount + 1) {
      return { ...edit, oldText: '', replacement: eol + newText };
    }
    return { ...edit, oldText: '', replacement: newText + eol, anchorLine: doc.lineAt(edit.startLine - 1).text };
  }

  if (edit.endLine > lineCount) {
    throw new Error(`${edit.file}: line ${edit.endLine} is past the end of the file (${lineCount} lines)`);
  }
  const range = lineRange(doc, edit.startLine, edit.endLine);
  const oldText = doc.getText(range);
  const endsWithEol = edit.endLine < lineCount;
  let replacement = newText;
  if (endsWithEol && newText !== '') {
    replacement += eol;
  }
  return { ...edit, oldText, replacement };
}

function lineRange(doc: vscode.TextDocument, startLine: number, endLine: number): vscode.Range {
  const start = new vscode.Position(startLine - 1, 0);
  const end = endLine < doc.lineCount ? new vscode.Position(endLine, 0) : doc.lineAt(endLine - 1).range.end;
  return new vscode.Range(start, end);
}

/** Finds the region of a prepared edit in the document as it is now. */
export function locate(doc: vscode.TextDocument, e: PreparedEdit): vscode.Range {
  if (!e.oldText) {
    if (e.anchorLine === undefined) {
      return new vscode.Range(doc.lineAt(doc.lineCount - 1).range.end, doc.lineAt(doc.lineCount - 1).range.end);
    }
    if (e.startLine - 1 < doc.lineCount && doc.lineAt(e.startLine - 1).text === e.anchorLine) {
      const p = new vscode.Position(e.startLine - 1, 0);
      return new vscode.Range(p, p);
    }
    throw new Error(`${e.file}: the code near line ${e.startLine} changed. Ask for a new suggestion.`);
  }

  if (e.endLine <= doc.lineCount) {
    const range = lineRange(doc, e.startLine, e.endLine);
    if (doc.getText(range) === e.oldText) {
      return range;
    }
  }
  const text = doc.getText();
  const first = text.indexOf(e.oldText);
  if (first !== -1 && text.indexOf(e.oldText, first + 1) === -1) {
    return new vscode.Range(doc.positionAt(first), doc.positionAt(first + e.oldText.length));
  }
  throw new Error(`${e.file}: the code at lines ${e.startLine}-${e.endLine} changed. Ask for a new suggestion.`);
}

export interface ApplyResult<T> {
  applied: T[];
  failed: { owner: T; reason: string }[];
}

/**
 * Applies the edits of many owners (threads) in one WorkspaceEdit.
 * An owner is applied only when all its edits can be located and none overlaps an edit of another owner.
 */
export async function applyGrouped<T>(root: string, groups: { owner: T; edits: PreparedEdit[] }[]): Promise<ApplyResult<T>> {
  const failed: { owner: T; reason: string }[] = [];
  type Located = { owner: T; uri: vscode.Uri; doc: vscode.TextDocument; range: vscode.Range; text: string };
  const candidates: { owner: T; items: Located[] }[] = [];

  for (const g of groups) {
    try {
      const items: Located[] = [];
      for (const e of g.edits) {
        const uri = vscode.Uri.file(resolveInRoot(root, e.file));
        const doc = await vscode.workspace.openTextDocument(uri);
        items.push({ owner: g.owner, uri, doc, range: locate(doc, e), text: e.replacement });
      }
      candidates.push({ owner: g.owner, items });
    } catch (err) {
      failed.push({ owner: g.owner, reason: (err as Error).message });
    }
  }

  // Reject owners whose edits overlap edits of owners that come earlier.
  const flat = candidates.flatMap((c, ci) => c.items.map((it) => ({ ci, it })));
  const byFile = new Map<string, typeof flat>();
  for (const f of flat) {
    const key = f.it.uri.toString();
    byFile.set(key, [...(byFile.get(key) ?? []), f]);
  }
  const rejectedOwners = new Set<number>();
  for (const list of byFile.values()) {
    const ranges = list.map((f) => ({ start: f.it.doc.offsetAt(f.it.range.start), end: f.it.doc.offsetAt(f.it.range.end) }));
    for (const i of findOverlaps(ranges)) {
      rejectedOwners.add(list[i].ci);
    }
  }

  const wsEdit = new vscode.WorkspaceEdit();
  const applied: T[] = [];
  candidates.forEach((c, ci) => {
    if (rejectedOwners.has(ci)) {
      failed.push({ owner: c.owner, reason: 'its edit overlaps another fix; apply it alone after the others' });
      return;
    }
    for (const it of c.items) {
      wsEdit.replace(it.uri, it.range, it.text);
    }
    applied.push(c.owner);
  });

  if (applied.length && !(await vscode.workspace.applyEdit(wsEdit))) {
    return { applied: [], failed: [...failed, ...applied.map((owner) => ({ owner, reason: 'VS Code rejected the edit' }))] };
  }
  return { applied, failed };
}

/** Returns the full text of each changed file as it will be after the edits, without changing anything. */
export async function previewTexts(root: string, edits: PreparedEdit[]): Promise<Map<string, string>> {
  const byFile = new Map<string, PreparedEdit[]>();
  for (const e of edits) {
    byFile.set(e.file, [...(byFile.get(e.file) ?? []), e]);
  }
  const out = new Map<string, string>();
  for (const [file, list] of byFile) {
    const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(resolveInRoot(root, file)));
    const ranges = list.map((e) => ({ e, start: doc.offsetAt(locate(doc, e).start), end: doc.offsetAt(locate(doc, e).end) }));
    if (findOverlaps(ranges).size) {
      throw new Error(`${file}: the edits of this fix overlap`);
    }
    let text = doc.getText();
    for (const r of ranges.sort((a, b) => b.start - a.start)) {
      text = text.slice(0, r.start) + r.e.replacement + text.slice(r.end);
    }
    out.set(file, text);
  }
  return out;
}
