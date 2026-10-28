import * as vscode from 'vscode';
import { TextEdit } from './parse';

export type ThreadSource = 'gitlab' | 'self' | 'pipeline';

export interface CrNote {
  id?: number;
  authorId?: number;
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
  /** Set when the user applied or discarded the fix, so the automatic run and Fix All do not make it again. */
  skipAutoFix = false;
  /** Set when the user discarded the reply draft. */
  skipAutoReply = false;
  vsThread?: vscode.CommentThread;
  /** Commit the note's line number refers to (GitLab position head_sha). */
  positionSha?: string;
  /** Line number on GitLab, before it was moved to the local file. */
  gitlabLine?: number;
  /** The line of the note was changed or deleted locally after the note was written. */
  outdated = false;
  /** Short label for the thread header, for example the CI job name. */
  subtitle?: string;
  /** For a CI failure: the job web URL. */
  jobUrl?: string;

  constructor(
    readonly id: string,
    readonly source: ThreadSource,
    public notes: CrNote[],
    /** Path relative to the repository root. */
    readonly file: string | undefined,
    /** 1-based line in the current version of the file. */
    public line: number | undefined,
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

  /** Changes when a new note is added, so cached suggestions for older conversations are not reused. */
  get cacheKey(): string {
    return `${this.id}:${this.notes[this.notes.length - 1]?.id ?? this.notes.length}`;
  }

  /** True when nothing shown for this thread changed between two loads. */
  sameAs(o: CrThread): boolean {
    return (
      this.cacheKey === o.cacheKey &&
      this.file === o.file &&
      this.line === o.line &&
      this.outdated === o.outdated &&
      this.resolved === o.resolved &&
      this.resolvable === o.resolvable
    );
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
