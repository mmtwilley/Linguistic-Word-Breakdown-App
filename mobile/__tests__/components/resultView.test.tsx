/**
 * ResultView tests (T022): FR-008 array-order rendering, US1-AS3 null-field
 * omission, loading state.
 */
import { render, screen } from "@testing-library/react-native";

import type { WordCard } from "../../src/api/types";
import ResultView from "../../src/components/ResultView";
import WordCardView from "../../src/components/WordCardView";
import { engLowNotCovered, korMediumOrderWarning } from "../../src/testing/analysisFixtures";

type RenderedTree = ReturnType<typeof screen.toJSON>;

function countTextNodes(node: RenderedTree | RenderedTree[] | string | null): number {
  if (node === null || typeof node === "string") {
    return 0;
  }
  if (Array.isArray(node)) {
    return node.reduce((sum, child) => sum + countTextNodes(child), 0);
  }
  const self = node.type === "Text" ? 1 : 0;
  return self + countTextNodes((node.children ?? []) as RenderedTree[]);
}

describe("ResultView", () => {
  it("shows the loading state while submitting", async () => {
    await render(<ResultView loading={true} result={korMediumOrderWarning} />);
    expect(screen.getByText("Analyzing…")).toBeOnTheScreen();
    expect(screen.queryByText("The weather is really nice today.")).not.toBeOnTheScreen();
  });

  it("renders nothing before the first result", async () => {
    await render(<ResultView loading={false} result={null} />);
    expect(screen.toJSON()).toBeNull();
  });

  it("renders translation and cards exactly in array order (kor fixture)", async () => {
    await render(<ResultView loading={false} result={korMediumOrderWarning} />);
    expect(screen.getByText("The weather is really nice today.")).toBeOnTheScreen();

    // The fixture arrives with 날씨가 out of sentence position on purpose
    // (CARDS_OUT_OF_ORDER) — rendering must preserve that order, not fix it.
    const rendered = JSON.stringify(screen.toJSON());
    const positions = korMediumOrderWarning.words.map((w) => rendered.indexOf(w.surface));
    expect(positions).not.toContain(-1);
    expect([...positions].sort((a, b) => a - b)).toEqual(positions);
  });

  it("omits null romanization entirely (eng fixture)", async () => {
    await render(<ResultView loading={false} result={engLowNotCovered} />);
    expect(screen.getByText("She")).toBeOnTheScreen();
    const rendered = JSON.stringify(screen.toJSON());
    expect(rendered).not.toContain("null");
  });
});

describe("WordCardView null-field omission", () => {
  it("renders only the surface when every optional field is null", async () => {
    const bare: WordCard = {
      surface: "먹었어요",
      lemma: null,
      pos: null,
      gloss: null,
      romanization: null,
      ipa: null,
    };
    await render(<WordCardView card={bare} />);
    expect(screen.getByText("먹었어요")).toBeOnTheScreen();
    // Exactly one text node: nothing rendered for the null fields.
    expect(countTextNodes(screen.toJSON())).toBe(1);
  });

  it("hides the lemma row when lemma equals surface", async () => {
    const card: WordCard = {
      surface: "오늘",
      lemma: "오늘",
      pos: "noun",
      gloss: "today",
      romanization: "oneul",
      ipa: null,
    };
    await render(<WordCardView card={card} />);
    expect(screen.queryByText(/base form/)).not.toBeOnTheScreen();
  });
});
