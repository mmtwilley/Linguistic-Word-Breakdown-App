/**
 * Envelope/network parsing tests (T013) for the typed fetch client.
 * Every outcome — 2xx, structured non-2xx, malformed bodies, fetch failure,
 * caller abort — must come back as an ApiResult, never a thrown exception.
 */
import { request } from "../../src/api/client";

// getBaseUrl() requires EXPO_PUBLIC_API_URL; give tests a deterministic one.
process.env.EXPO_PUBLIC_API_URL = "http://localhost:8080";

const fetchMock = jest.fn();
globalThis.fetch = fetchMock as unknown as typeof fetch;

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

function unreadableResponse(status: number): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.reject(new SyntaxError("not json")),
  } as unknown as Response;
}

beforeEach(() => {
  fetchMock.mockReset();
});

describe("request()", () => {
  it("parses a 2xx body as typed data", async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { accessToken: "a", refreshToken: "r" }));

    const result = await request<{ accessToken: string }>("/api/auth/login", {
      method: "POST",
      body: { email: "x@y.z", password: "pw" },
    });

    expect(result).toEqual({ ok: true, data: { accessToken: "a", refreshToken: "r" } });
    expect(fetchMock).toHaveBeenCalledWith(
      "http://localhost:8080/api/auth/login",
      expect.objectContaining({ method: "POST" }),
    );
  });

  it("passes the Bearer token and JSON headers", async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, {}));

    await request("/api/analyze", { method: "POST", body: { text: "hi" }, accessToken: "tok" });

    const init = fetchMock.mock.calls[0][1];
    expect(init.headers).toMatchObject({
      Authorization: "Bearer tok",
      "Content-Type": "application/json",
      Accept: "application/json",
    });
    expect(init.body).toBe(JSON.stringify({ text: "hi" }));
  });

  it("parses a structured non-2xx envelope and attaches the HTTP status", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(401, {
        error: { code: "UNAUTHORIZED", message: "Token expired.", retryable: false },
      }),
    );

    const result = await request("/api/analyze", { method: "POST", body: { text: "hi" } });

    expect(result).toEqual({
      ok: false,
      error: { code: "UNAUTHORIZED", message: "Token expired.", retryable: false, status: 401 },
    });
  });

  it("synthesizes INTERNAL_ERROR for a non-2xx body that is not an envelope", async () => {
    fetchMock.mockResolvedValue(jsonResponse(502, "Bad Gateway"));

    const result = await request("/x", { method: "GET" });

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.code).toBe("INTERNAL_ERROR");
      expect(result.error.retryable).toBe(true); // 5xx → retry may succeed
      expect(result.error.status).toBe(502);
    }
  });

  it("synthesizes a non-retryable INTERNAL_ERROR for an unparseable 4xx body", async () => {
    fetchMock.mockResolvedValue(unreadableResponse(404));

    const result = await request("/x", { method: "GET" });

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.code).toBe("INTERNAL_ERROR");
      expect(result.error.retryable).toBe(false); // 4xx → retrying won't help
    }
  });

  it("synthesizes INTERNAL_ERROR for an unreadable 2xx body", async () => {
    fetchMock.mockResolvedValue(unreadableResponse(200));

    const result = await request("/x", { method: "GET" });

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.code).toBe("INTERNAL_ERROR");
      expect(result.error.status).toBe(200);
    }
  });

  it("synthesizes retryable NETWORK_ERROR when fetch itself fails", async () => {
    fetchMock.mockRejectedValue(new TypeError("Network request failed"));

    const result = await request("/x", { method: "GET" });

    expect(result).toEqual({
      ok: false,
      error: { code: "NETWORK_ERROR", message: expect.any(String), retryable: true },
    });
  });

  it("resolves (not throws) when the caller aborts", async () => {
    const controller = new AbortController();
    fetchMock.mockImplementation(
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => {
          init.signal?.addEventListener("abort", () => reject(new Error("Aborted")));
        }),
    );

    const pending = request("/x", { method: "GET", signal: controller.signal });
    controller.abort();

    const result = await pending;
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.code).toBe("NETWORK_ERROR");
    }
  });
});
