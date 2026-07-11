/**
 * AnalyzeInput gating tests (T022): FR-015 char limit, FR-012 in-flight lock.
 */
import { fireEvent, render, screen } from "@testing-library/react-native";

import AnalyzeInput, { canSubmitText, MAX_TEXT_LENGTH } from "../../src/components/AnalyzeInput";

const noop = () => {};

function analyzeButton() {
  return screen.getByRole("button", { name: "Analyze" });
}

describe("canSubmitText", () => {
  it("rejects blank and whitespace-only text", () => {
    expect(canSubmitText("", false)).toBe(false);
    expect(canSubmitText("   \n", false)).toBe(false);
  });

  it("accepts 1..500 chars and rejects 501", () => {
    expect(canSubmitText("a", false)).toBe(true);
    expect(canSubmitText("a".repeat(MAX_TEXT_LENGTH), false)).toBe(true);
    expect(canSubmitText("a".repeat(MAX_TEXT_LENGTH + 1), false)).toBe(false);
  });

  it("rejects while a request is in flight", () => {
    expect(canSubmitText("hello", true)).toBe(false);
  });
});

describe("AnalyzeInput", () => {
  it("disables submit for blank text", async () => {
    await render(<AnalyzeInput value="" onChangeText={noop} onSubmit={noop} submitting={false} />);
    expect(analyzeButton()).toBeDisabled();
  });

  it("enables submit for valid text and fires onSubmit", async () => {
    const onSubmit = jest.fn();
    await render(
      <AnalyzeInput value="점심을 먹었어요" onChangeText={noop} onSubmit={onSubmit} submitting={false} />,
    );
    expect(analyzeButton()).toBeEnabled();
    await fireEvent.press(analyzeButton());
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it("keeps over-limit pastes visible but blocks submit and flags the counter", async () => {
    const long = "a".repeat(MAX_TEXT_LENGTH + 23);
    await render(
      <AnalyzeInput value={long} onChangeText={noop} onSubmit={noop} submitting={false} />,
    );
    expect(screen.getByLabelText("Text to analyze").props.value).toBe(long);
    expect(screen.getByText(`${MAX_TEXT_LENGTH + 23}/${MAX_TEXT_LENGTH}`)).toBeOnTheScreen();
    expect(analyzeButton()).toBeDisabled();
  });

  it("locks input and submit while submitting", async () => {
    await render(
      <AnalyzeInput value="hello" onChangeText={noop} onSubmit={noop} submitting={true} />,
    );
    expect(analyzeButton()).toBeDisabled();
    expect(screen.getByLabelText("Text to analyze").props.editable).toBe(false);
  });

  it("renders the inline error when provided", async () => {
    await render(
      <AnalyzeInput
        value="x"
        onChangeText={noop}
        onSubmit={noop}
        submitting={false}
        errorText="Text must be between 1 and 500 characters."
      />,
    );
    expect(screen.getByText("Text must be between 1 and 500 characters.")).toBeOnTheScreen();
  });
});
