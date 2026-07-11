/**
 * Draft preservation across forced re-auth (T025, FR-004).
 *
 * Module-level memory only — the draft is user-typed text and must never
 * touch disk or secure storage (FR-018). Losing it on a full app restart is
 * the accepted trade-off (data-model.md `draft`).
 *
 * Wiring: HomeScreen registers a provider for its live input state; the
 * refresh-failure path in useAuthedFetch calls stashCurrentDraft() just
 * before clearing the session; HomeScreen's next mount (after re-login)
 * takes the stash back. Explicit sign-out must call clearDraft() (T026) —
 * deliberately NOT inside AuthContext.signOut, because the refresh-failure
 * path stashes immediately before calling signOut and a shared clear would
 * wipe what was just saved.
 */
import type { LanguageHint } from "../api/types";

export interface Draft {
  text: string;
  language: LanguageHint | null;
}

let provider: (() => Draft) | null = null;
let stash: Draft | null = null;

/** HomeScreen registers/unregisters its live input state here. */
export function setDraftProvider(fn: (() => Draft) | null): void {
  provider = fn;
}

/** Snapshot the live draft (no-op when no screen is registered). */
export function stashCurrentDraft(): void {
  if (provider) {
    stash = provider();
  }
}

/** Read-and-clear: a draft is restored at most once. */
export function takeDraft(): Draft | null {
  const taken = stash;
  stash = null;
  return taken;
}

/** Explicit sign-out discards any pending draft (FR-004). */
export function clearDraft(): void {
  stash = null;
}
