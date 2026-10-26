import * as vscode from 'vscode';
import { TextEdit } from './parse';

export type ThreadSource = 'gitlab' | 'self';

export interface CrNote {
  author: string;
  avatarUrl?: string;
  body: string;
  createdAt?: string;
}

/**
 * A text edit resolved against the document when the suggestion was made.
 * `oldText` is the exact text of the replaced region and `replacement` the exact text that goes in,
 * so the edit can be found again after the document changes.
 */
export interface PreparedEdit extends TextEdit {
  oldText: string;
  replacement: string;
  /** For pure inserts: the text of the line that followed the insert point. */
  anchorLine?: string;
}

export interface FixSuggestion {
  explanation: string;
  edits: PreparedEdit[];
}

export class CrThread {
  suggestion?: FixSuggestion;
  draftReply?: string;
  vsThread?: vscode.CommentThread;

  constructor(
    readonly id: string,
    readonly source: ThreadSource,
    public notes: CrNote[],
    /** Path relative to the repository root. */
    readonly file: string | undefined,
    /** 1-based line in the current version of the file. */
    readonly line: number | undefined,
    public resolvable: boolean,
    public resolved: boolean,
    readonly discussionId?: string,
    readonly severity?: string,
  ) {}

  get title(): string {
    const first = this.notes[0]?.body ?? '';
    const line = first.split('\n').find((l) => l.trim()) ?? '';
    // Plain text for the sidebar: remove the most common markdown marks.
    return line.replace(/\*\*|__|`/g, '').trim().slice(0, 120) || '(empty)';
  }

  get location(): string {
    return this.file ? `${this.file}${this.line ? ':' + this.line : ''}` : 'General';
  }

  /** Context value used by `when` clauses in package.json menus. */
  get contextValue(): string {
    const parts = ['crThread', `src-${this.source}`];
    if (this.file) {
      parts.push('hasFile');
    }
    if (this.suggestion) {
      parts.push('hasFix');
    }
    if (this.resolvable) {
      parts.push('resolvable');
    }
    parts.push(this.resolved ? 'resolved' : 'unresolved');
    return parts.join(' ');
  }
}
