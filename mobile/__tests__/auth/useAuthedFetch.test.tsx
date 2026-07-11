/**
 * Single-flight token refresh (T027 ← T024, research.md Decision 3):
 * concurrent auth failures share ONE refresh, the rotated pair is persisted,
 * originals are retried once, and a rejected refresh token clears the
 * session (stashing the draft, T025). Includes the backend's 403-empty-body
 * variant for expired access tokens.
 */
import { act, renderHook, waitFor } from "@testing-library/react-native";
import type { ReactNode } from "react";

import { analyze } from "../../src/api/analyze";
import { setDraftProvider, takeDraft } from "../../src/auth/draftStash";
import { AuthProvider, useAuth } from "../../src/auth/AuthContext";
import { useAuthedFetch } from "../../src/auth/useAuthedFetch";
import { jpnHighClean } from "../../src/testing/analysisFixtures";

process.env.EXPO_PUBLIC_API_URL = "http://localhost:8080";

jest.mock("expo-secure-store", () => ({
  getItemAsync: jest.fn(),
  setItemAsync: jest.fn().mockResolvedValue(undefined),
  deleteItemAsync: jest.fn().mockResolvedValue(undefined),
}));
// eslint-disable-next-line import/first
import * as SecureStore from "expo-secure-store";
const secureStore = SecureStore as jest.Mocked<typeof SecureStore>;

const fetchMock = jest.fn();
globalThis.fetch = fetchMock as unknown as typeof fetch;

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) };
}

/** Non-2xx with an unreadable body — how the backend reports expired tokens. */
function emptyBodyResponse(status: number) {
  return { ok: false, status, json: () => Promise.reject(new Error("empty body")) };
}

function wrapper({ children }: { children: ReactNode }) {
  return <AuthProvider>{children}</AuthProvider>;
}

/** Renders the hook inside AuthProvider and waits for bootstrap → signedIn. */
async function renderAuthedFetch() {
  const view = await renderHook(() => ({ authedFetch: useAuthedFetch(), auth: useAuth() }), {
    wrapper,
  });
  await waitFor(() => expect(view.result.current.auth.status).toBe("signedIn"));
  return view;
}

beforeEach(() => {
  fetchMock.mockReset();
  secureStore.setItemAsync.mockClear();
  secureStore.deleteItemAsync.mockClear();
  secureStore.getItemAsync.mockResolvedValue(
    JSON.stringify({ accessToken: "tok", refreshToken: "ref" }),
  );
  takeDraft(); // drain module-level stash between tests
  setDraftProvider(null);
});

describe("single-flight refresh", () => {
  it("two concurrent 401s trigger exactly one refresh and both retries succeed", async () => {
    fetchMock.mockImplementation(async (url: string, init: { headers: Record<string, string>; body?: string }) => {
      if (url.endsWith("/api/auth/refresh")) {
        return jsonResponse(200, { accessToken: "tok2", refreshToken: "ref2", expiresIn: 900 });
      }
      if (init.headers.Authorization === "Bearer tok2") {
        return jsonResponse(200, jpnHighClean);
      }
      return jsonResponse(401, {
        error: { code: "UNAUTHORIZED", message: "Expired.", retryable: false },
      });
    });

    const { result } = await renderAuthedFetch();
    let outcomes: Awaited<ReturnType<typeof analyze>>[] = [];
    await act(async () => {
      outcomes = await Promise.all([
        result.current.authedFetch((token) => analyze({ text: "a" }, token)),
        result.current.authedFetch((token) => analyze({ text: "b" }, token)),
      ]);
    });

    expect(outcomes[0].ok).toBe(true);
    expect(outcomes[1].ok).toBe(true);

    const refreshCalls = fetchMock.mock.calls.filter(([url]) => url.endsWith("/api/auth/refresh"));
    expect(refreshCalls).toHaveLength(1);
    expect(JSON.parse(refreshCalls[0][1].body)).toEqual({ refreshToken: "ref" });
    // 2 original 401s + 1 refresh + 2 retries
    expect(fetchMock).toHaveBeenCalledTimes(5);
  });

  it("persists the rotated pair atomically to secure storage", async () => {
    fetchMock.mockImplementation(async (url: string, init: { headers: Record<string, string> }) => {
      if (url.endsWith("/api/auth/refresh")) {
        return jsonResponse(200, { accessToken: "tok2", refreshToken: "ref2", expiresIn: 900 });
      }
      return init.headers.Authorization === "Bearer tok2"
        ? jsonResponse(200, jpnHighClean)
        : jsonResponse(401, {
            error: { code: "UNAUTHORIZED", message: "Expired.", retryable: false },
          });
    });

    const { result } = await renderAuthedFetch();
    await act(async () => {
      await result.current.authedFetch((token) => analyze({ text: "a" }, token));
    });

    expect(secureStore.setItemAsync).toHaveBeenCalledWith(
      "lingua.auth.tokens",
      JSON.stringify({ accessToken: "tok2", refreshToken: "ref2" }),
    );
  });

  it("treats the backend's 403-with-empty-body as an auth failure and refreshes", async () => {
    fetchMock.mockImplementation(async (url: string, init: { headers: Record<string, string> }) => {
      if (url.endsWith("/api/auth/refresh")) {
        return jsonResponse(200, { accessToken: "tok2", refreshToken: "ref2", expiresIn: 900 });
      }
      return init.headers.Authorization === "Bearer tok2"
        ? jsonResponse(200, jpnHighClean)
        : emptyBodyResponse(403);
    });

    const { result } = await renderAuthedFetch();
    let outcome: Awaited<ReturnType<typeof analyze>> | undefined;
    await act(async () => {
      outcome = await result.current.authedFetch((token) => analyze({ text: "a" }, token));
    });

    expect(outcome?.ok).toBe(true);
    expect(
      fetchMock.mock.calls.filter(([url]) => url.endsWith("/api/auth/refresh")),
    ).toHaveLength(1);
  });

  it("INVALID_REFRESH_TOKEN clears the session and stashes the draft (FR-004)", async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (url.endsWith("/api/auth/refresh")) {
        return jsonResponse(401, {
          error: { code: "INVALID_REFRESH_TOKEN", message: "Rotated away.", retryable: false },
        });
      }
      return jsonResponse(401, {
        error: { code: "UNAUTHORIZED", message: "Expired.", retryable: false },
      });
    });

    const { result } = await renderAuthedFetch();
    setDraftProvider(() => ({ text: "안녕하세요", language: "kor" }));

    let outcome: Awaited<ReturnType<typeof analyze>> | undefined;
    await act(async () => {
      outcome = await result.current.authedFetch((token) => analyze({ text: "a" }, token));
    });

    // Original error surfaces; session is gone; draft survived.
    expect(outcome?.ok).toBe(false);
    await waitFor(() => expect(result.current.auth.status).toBe("signedOut"));
    expect(secureStore.deleteItemAsync).toHaveBeenCalledWith("lingua.auth.tokens");
    expect(takeDraft()).toEqual({ text: "안녕하세요", language: "kor" });
  });

  it("a network failure during refresh keeps the session (no sign-out)", async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (url.endsWith("/api/auth/refresh")) {
        throw new TypeError("Network request failed");
      }
      return jsonResponse(401, {
        error: { code: "UNAUTHORIZED", message: "Expired.", retryable: false },
      });
    });

    const { result } = await renderAuthedFetch();
    let outcome: Awaited<ReturnType<typeof analyze>> | undefined;
    await act(async () => {
      outcome = await result.current.authedFetch((token) => analyze({ text: "a" }, token));
    });

    expect(outcome?.ok).toBe(false);
    expect(result.current.auth.status).toBe("signedIn");
    expect(secureStore.deleteItemAsync).not.toHaveBeenCalled();
  });
});
