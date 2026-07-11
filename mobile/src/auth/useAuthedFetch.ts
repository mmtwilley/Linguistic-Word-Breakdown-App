/**
 * Authenticated request wrapper with single-flight token refresh (T024,
 * research.md Decision 3).
 *
 * Flow: attach the current access token → on an auth failure, refresh once
 * (all concurrent failures await the SAME refresh promise — rotation means
 * two parallel refreshes would invalidate each other) → persist the rotated
 * pair atomically via AuthContext.applyTokens → retry the original request
 * once. If the refresh token itself is rejected, the session is cleared
 * (FR-004) and the root navigator returns to sign-in.
 *
 * Auth failures are detected by HTTP status (401 OR 403), not envelope code:
 * the backend responds 403 with an EMPTY body for expired/invalid access
 * tokens (no envelope, so the client synthesizes INTERNAL_ERROR — the status
 * field is the only reliable signal). 401 UNAUTHORIZED is kept for when the
 * backend is fixed to match the contract.
 */
import { useCallback } from "react";

import { refresh } from "../api/auth";
import type { ApiError, ApiResult } from "../api/client";
import { useAuth, type AuthContextValue } from "./AuthContext";
import { stashCurrentDraft } from "./draftStash";
import type { TokenPair } from "./tokenStorage";

/** One request attempt with the given access token. */
export type Executor<T> = (accessToken: string) => Promise<ApiResult<T>>;

/**
 * Module scope, not hook state: every useAuthedFetch() instance across the
 * app must share one in-flight refresh. Resolves to the rotated pair, or
 * null when the session could not be salvaged.
 */
let inflightRefresh: Promise<TokenPair | null> | null = null;

function isAuthFailure(error: ApiError): boolean {
  return error.status === 401 || error.status === 403;
}

const SIGNED_OUT: ApiError = {
  code: "UNAUTHORIZED",
  message: "Your session has expired.",
  retryable: false,
};

/**
 * Exchanges the refresh token for a rotated pair. Rejection by the server
 * (INVALID_REFRESH_TOKEN, or the 403-empty-body variant) clears the session;
 * a network failure does NOT — signing the user out over a dead connection
 * would destroy a session that is still valid server-side.
 */
async function runRefresh(auth: AuthContextValue): Promise<TokenPair | null> {
  const refreshToken = auth.getTokens()?.refreshToken;
  if (!refreshToken) {
    return null;
  }
  const result = await refresh({ refreshToken });
  if (!result.ok) {
    if (result.error.status !== undefined && result.error.status < 500) {
      // Server rejected the token — session is dead (FR-004). Snapshot the
      // user's typed draft first so it survives the forced re-login (T025).
      stashCurrentDraft();
      await auth.signOut();
    }
    return null;
  }
  const pair: TokenPair = {
    accessToken: result.data.accessToken,
    refreshToken: result.data.refreshToken,
  };
  await auth.applyTokens(pair); // persists atomically, keeps "signedIn"
  return pair;
}

/**
 * Returns a stable function that runs `execute` with the current access
 * token, transparently refreshing and retrying once on auth failure.
 *
 * Callers close over their own body/signal:
 *   const authedFetch = useAuthedFetch();
 *   const result = await authedFetch((token) => analyze(body, token, signal));
 */
export function useAuthedFetch() {
  const auth = useAuth();

  return useCallback(
    async <T>(execute: Executor<T>): Promise<ApiResult<T>> => {
      const tokenUsed = auth.getTokens()?.accessToken;
      if (!tokenUsed) {
        return { ok: false, error: { ...SIGNED_OUT } };
      }

      const first = await execute(tokenUsed);
      if (first.ok || !isAuthFailure(first.error)) {
        return first;
      }

      // Another caller may have already rotated the pair while our request
      // was in flight — if so, retry with the fresh token instead of burning
      // a second rotation on a refresh token that still works.
      const current = auth.getTokens();
      if (current && current.accessToken !== tokenUsed) {
        return execute(current.accessToken);
      }

      if (!inflightRefresh) {
        inflightRefresh = runRefresh(auth).finally(() => {
          inflightRefresh = null;
        });
      }
      const rotated = await inflightRefresh;
      if (!rotated) {
        // Session cleared (navigator is swapping to Login) or refresh hit a
        // transient failure; either way the original error stands.
        return first;
      }
      return execute(rotated.accessToken); // retry exactly once
    },
    [auth],
  );
}
