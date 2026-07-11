/**
 * Draft stash semantics (T027 ← T025, FR-004/FR-018): snapshot-through-
 * provider, restore-at-most-once, and explicit clear.
 */
import {
  clearDraft,
  setDraftProvider,
  stashCurrentDraft,
  takeDraft,
} from "../../src/auth/draftStash";

beforeEach(() => {
  takeDraft(); // drain module state
  setDraftProvider(null);
});

describe("draftStash", () => {
  it("stashes the provider's live snapshot and restores it once", () => {
    setDraftProvider(() => ({ text: "오늘 날씨", language: "kor" }));
    stashCurrentDraft();

    expect(takeDraft()).toEqual({ text: "오늘 날씨", language: "kor" });
    // Read-and-clear: a draft never restores twice.
    expect(takeDraft()).toBeNull();
  });

  it("snapshots the values at stash time, not at take time", () => {
    let text = "first";
    setDraftProvider(() => ({ text, language: null }));
    stashCurrentDraft();
    text = "second"; // later typing must not rewrite the stash

    expect(takeDraft()).toEqual({ text: "first", language: null });
  });

  it("is a no-op when no provider is registered", () => {
    stashCurrentDraft();
    expect(takeDraft()).toBeNull();
  });

  it("clearDraft discards a pending draft (explicit sign-out, FR-004)", () => {
    setDraftProvider(() => ({ text: "draft", language: null }));
    stashCurrentDraft();
    clearDraft();

    expect(takeDraft()).toBeNull();
  });
});
