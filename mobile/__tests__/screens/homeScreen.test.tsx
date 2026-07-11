/**
 * HomeScreen flow tests (T022): submit → result, resubmit replaces (US1-AS4),
 * and the FR-013 per-code error placements (T021), all against mocked fetch.
 */
import { NavigationContainer } from "@react-navigation/native";
import { fireEvent, render, screen } from "@testing-library/react-native";

import { AuthProvider } from "../../src/auth/AuthContext";
import HomeScreen from "../../src/screens/HomeScreen";
import { jpnHighClean, korMediumOrderWarning } from "../../src/testing/analysisFixtures";

process.env.EXPO_PUBLIC_API_URL = "http://localhost:8080";

// A stored session so AuthContext bootstraps straight to signedIn.
jest.mock("expo-secure-store", () => ({
  getItemAsync: jest
    .fn()
    .mockResolvedValue(JSON.stringify({ accessToken: "tok", refreshToken: "ref" })),
  setItemAsync: jest.fn().mockResolvedValue(undefined),
  deleteItemAsync: jest.fn().mockResolvedValue(undefined),
}));

const fetchMock = jest.fn();
globalThis.fetch = fetchMock as unknown as typeof fetch;

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) };
}

async function renderHome() {
  await render(
    <AuthProvider>
      <NavigationContainer>
        <HomeScreen />
      </NavigationContainer>
    </AuthProvider>,
  );
  // Wait for auth bootstrap so getTokens() is populated before submitting.
  await screen.findByRole("button", { name: "Analyze" });
}

async function typeAndSubmit(text: string) {
  // RNTL v14 concurrent mode: fireEvent returns a promise — not awaiting it
  // causes overlapping act() calls that poison every render after it.
  await fireEvent.changeText(screen.getByLabelText("Text to analyze"), text);
  await fireEvent.press(screen.getByRole("button", { name: "Analyze" }));
}

beforeEach(() => {
  fetchMock.mockReset();
});

describe("HomeScreen submit flow", () => {
  it("submits with Bearer token and renders the result", async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, korMediumOrderWarning));
    await renderHome();
    await typeAndSubmit("오늘 날씨가 정말 좋네요");

    expect(await screen.findByText("The weather is really nice today.")).toBeOnTheScreen();

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://localhost:8080/api/analyze");
    expect(init.headers.Authorization).toBe("Bearer tok");
    expect(JSON.parse(init.body)).toEqual({ text: "오늘 날씨가 정말 좋네요" }); // auto = no language field
  });

  it("sends the language hint when an override is picked", async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, jpnHighClean));
    await renderHome();
    await fireEvent.press(screen.getByRole("radio", { name: "日本語" }));
    await typeAndSubmit("私は毎朝コーヒーを飲みます");

    await screen.findByText("I drink coffee every morning.");
    expect(JSON.parse(fetchMock.mock.calls[0][1].body).language).toBe("jpn");
  });

  it("replaces the previous result on resubmit (US1-AS4)", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, korMediumOrderWarning));
    await renderHome();
    await typeAndSubmit("오늘 날씨가 정말 좋네요");
    await screen.findByText("The weather is really nice today.");

    fetchMock.mockResolvedValueOnce(jsonResponse(200, jpnHighClean));
    await typeAndSubmit("私は毎朝コーヒーを飲みます");

    expect(await screen.findByText("I drink coffee every morning.")).toBeOnTheScreen();
    expect(screen.queryByText("The weather is really nice today.")).not.toBeOnTheScreen();
  });
});

describe("HomeScreen error placement (FR-013)", () => {
  it("INVALID_INPUT shows inline at the input, no banner", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(400, {
        error: { code: "INVALID_INPUT", message: "Text too long.", retryable: false },
      }),
    );
    await renderHome();
    await typeAndSubmit("x");

    expect(
      await screen.findByText("Text must be between 1 and 500 characters."),
    ).toBeOnTheScreen();
    expect(screen.queryByRole("button", { name: "Retry" })).not.toBeOnTheScreen();
  });

  it("LANGUAGE_UNDETECTABLE suggests the picker", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(422, {
        error: { code: "LANGUAGE_UNDETECTABLE", message: "Cannot detect.", retryable: false },
      }),
    );
    await renderHome();
    await typeAndSubmit("zzzz");

    expect(await screen.findByText(/picking the language/)).toBeOnTheScreen();
  });

  it("RATE_LIMIT_EXCEEDED shows the wait message", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(429, {
        error: { code: "RATE_LIMIT_EXCEEDED", message: "Slow down.", retryable: true },
      }),
    );
    await renderHome();
    await typeAndSubmit("hello");

    expect(await screen.findByText(/Give it a moment/)).toBeOnTheScreen();
  });

  it("network failure shows the retriable banner, and Retry resubmits", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("Network request failed"));
    await renderHome();
    await typeAndSubmit("hello");

    expect(await screen.findByText(/Check your connection/)).toBeOnTheScreen();

    fetchMock.mockResolvedValueOnce(jsonResponse(200, jpnHighClean));
    await fireEvent.press(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("I drink coffee every morning.")).toBeOnTheScreen();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
