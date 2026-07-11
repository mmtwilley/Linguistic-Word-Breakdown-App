/**
 * Contract-fixture test (T013): the 002 backend's real responses must satisfy
 * types.ts. The heavy lifting is at compile time — analysisFixtures.ts
 * annotates each captured response as AnalysisResult, so contract drift is a
 * type error. The runtime assertions below pin the invariants the UI relies
 * on (data-model.md display routing, FR-008 ordering source).
 */
import type { AnalysisResult, IssueCode } from "../../src/api/types";
import { isResultLevel } from "../../src/i18n/messages";
import { allFixtures } from "../../src/testing/analysisFixtures";

const fixtures: [string, AnalysisResult][] = Object.entries(allFixtures);

describe("002 contract fixtures vs types.ts", () => {
  test.each(fixtures)("%s: confidence and issues are always present", (_name, result) => {
    expect(["high", "medium", "low"]).toContain(result.confidence);
    expect(Array.isArray(result.issues)).toBe(true);
    expect(Array.isArray(result.words)).toBe(true);
  });

  test.each(fixtures)("%s: issues route to a card or the result, never nowhere", (_name, result) => {
    for (const issue of result.issues) {
      if (isResultLevel(issue.code, issue.surface)) {
        continue; // rendered as a result-level notice
      }
      // Card-level issues must point at a card that exists in this response.
      expect(result.words.map((w) => w.surface)).toContain(issue.surface);
    }
  });

  it("kor fixture: medium confidence with a card-level order warning", () => {
    const { confidence, issues } = allFixtures.korMediumOrderWarning;
    expect(confidence).toBe("medium");
    expect(issues).toHaveLength(1);
    expect(issues[0].code).toBe("CARDS_OUT_OF_ORDER");
    expect(issues[0].surface).toBe("날씨가");
  });

  it("jpn fixture: high confidence and clean", () => {
    expect(allFixtures.jpnHighClean.confidence).toBe("high");
    expect(allFixtures.jpnHighClean.issues).toHaveLength(0);
  });

  it("cmn fixture: empty words still carries a translation and EMPTY_ANALYSIS", () => {
    const { translation, words, confidence, issues } = allFixtures.cmnLowEmptyAnalysis;
    expect(words).toHaveLength(0);
    expect(translation).not.toBeNull();
    expect(confidence).toBe("low");
    expect(issues[0].code).toBe("EMPTY_ANALYSIS");
    expect(issues[0].surface).toBeNull();
  });

  it("eng fixture: null romanization on every card plus a result-level coverage error", () => {
    const { words, issues } = allFixtures.engLowNotCovered;
    expect(words.every((w) => w.romanization === null)).toBe(true);
    expect(issues[0].code).toBe("INPUT_NOT_COVERED");
    expect(isResultLevel(issues[0].code, issues[0].surface)).toBe(true);
  });

  it("IssueCode stays forward-compatible: unknown codes are assignable and route visibly", () => {
    // Compile-time: the backend registry is append-only, so a code this app
    // version has never heard of must still satisfy IssueCode.
    const futureCode: IssueCode = "SOME_FUTURE_CODE";
    // Runtime: unknown codes always surface at result level, even with a surface.
    expect(isResultLevel(futureCode, "어떤단어")).toBe(true);
  });
});
