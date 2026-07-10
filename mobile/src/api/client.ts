/**
 * Typed fetch wrapper — the single path for all backend traffic.
 *
 * Guarantees to callers:
 * - Success and failure are a discriminated union (`ApiResult`), never thrown
 *   exceptions, so screens must handle both branches to compile.
 * - Every failure is a well-formed error envelope: non-2xx bodies are parsed,
 *   malformed/absent bodies and network failures are synthesized (FR-013/014).
 * - Raw response payloads never escape this module.
 */
import type { ErrorEnvelope } from "./types";

export type ApiError = ErrorEnvelope["error"] & {
  /** HTTP status when a response was received; undefined for network failures. */
  status?: number;
};

export type ApiResult<T> = { ok: true; data: T } | { ok: false; error: ApiError };

/** Hosts allowed to use plain http (constitution Principle II: local-only). */
const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "10.0.2.2"]);

const DEFAULT_TIMEOUT_MS = 30_000;

/**
 * Resolves and validates the backend base URL from EXPO_PUBLIC_API_URL.
 * Throws on misconfiguration — this is a developer error, not a runtime
 * condition to soften: better to fail loudly at startup than to silently
 * send credentials over http to a non-local host.
 */
export function getBaseUrl(): string {
  const raw = process.env.EXPO_PUBLIC_API_URL;
  if (!raw) {
    throw new Error("EXPO_PUBLIC_API_URL is not set. Copy mobile/.env.example to mobile/.env.");
  }
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new Error(`EXPO_PUBLIC_API_URL is not a valid URL: ${raw}`);
  }
  if (url.protocol !== "https:" && !LOCAL_HOSTS.has(url.hostname)) {
    throw new Error(
      `EXPO_PUBLIC_API_URL must use https for non-local hosts (got ${url.protocol}//${url.hostname}).`,
    );
  }
  // Strip trailing slash so request paths can always start with "/".
  return raw.replace(/\/+$/, "");
}

interface RequestOptions {
  method: "GET" | "POST";
  /** JSON-serialized as the request body. */
  body?: unknown;
  /** Attached as an Authorization: Bearer header. */
  accessToken?: string;
  /** Caller-side cancellation (e.g. screen unmount). */
  signal?: AbortSignal;
  timeoutMs?: number;
}

const NETWORK_ERROR: ApiError = {
  code: "NETWORK_ERROR",
  message: "Could not reach the server.",
  retryable: true,
};

/**
 * Executes a request and normalizes every outcome into ApiResult.
 * `T` is trusted, not verified: the wire contract (types.ts) is enforced by
 * the backend and the contract tests, not by runtime validation here.
 */
export async function request<T>(path: string, options: RequestOptions): Promise<ApiResult<T>> {
  const { method, body, accessToken, signal, timeoutMs = DEFAULT_TIMEOUT_MS } = options;

  const headers: Record<string, string> = { Accept: "application/json" };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }

  // Combine the caller's signal with our timeout.
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  const onCallerAbort = () => controller.abort();
  signal?.addEventListener("abort", onCallerAbort);

  let response: Response;
  try {
    response = await fetch(`${getBaseUrl()}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
  } catch {
    // Offline, DNS failure, timeout, or caller abort. Callers that aborted
    // discard the result anyway; everyone else gets a retriable envelope.
    return { ok: false, error: { ...NETWORK_ERROR } };
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener("abort", onCallerAbort);
  }

  if (response.ok) {
    try {
      return { ok: true, data: (await response.json()) as T };
    } catch {
      return {
        ok: false,
        error: {
          code: "INTERNAL_ERROR",
          message: "The server returned an unreadable response.",
          retryable: true,
          status: response.status,
        },
      };
    }
  }

  // Non-2xx: expect the structured envelope; synthesize one if absent/malformed.
  try {
    const envelope = (await response.json()) as ErrorEnvelope;
    if (envelope && envelope.error && typeof envelope.error.code === "string") {
      return { ok: false, error: { ...envelope.error, status: response.status } };
    }
  } catch {
    // fall through to synthesized error
  }
  return {
    ok: false,
    error: {
      code: "INTERNAL_ERROR",
      message: "The server returned an unexpected error.",
      retryable: response.status >= 500,
      status: response.status,
    },
  };
}
