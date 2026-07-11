/**
 * Session state machine (data-model.md):
 *
 *   app launch → "bootstrapping" → tokens found    → "signedIn"
 *                                → no/bad tokens   → "signedOut"
 *   signIn success:  "signedOut" → "signedIn"
 *   signOut/expiry:  "signedIn"  → "signedOut"
 *
 * Invariant: status can only be "signedIn" while a token pair exists in
 * secure storage — every transition persists first, then updates state.
 * Token values never appear in logs (FR-018).
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";

import { login } from "../api/auth";
import type { ApiError } from "../api/client";
import { clearTokens, loadTokens, saveTokens, type TokenPair } from "./tokenStorage";

export type AuthStatus = "bootstrapping" | "signedOut" | "signedIn";

export interface AuthContextValue {
  status: AuthStatus;
  /** Resolves to null on success, or the ApiError for the form to display. */
  signIn: (email: string, password: string) => Promise<ApiError | null>;
  signOut: () => Promise<void>;
  /** Current token pair (stable function identity; safe in effect deps). */
  getTokens: () => TokenPair | null;
  /** Persists a (possibly rotated) pair and enters/keeps "signedIn". */
  applyTokens: (tokens: TokenPair) => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>("bootstrapping");
  // Tokens live in a ref, not state: reading them must not re-render the
  // tree, and getTokens() must always see the latest pair mid-request.
  const tokensRef = useRef<TokenPair | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const stored = await loadTokens();
      if (cancelled) {
        return;
      }
      tokensRef.current = stored;
      setStatus(stored ? "signedIn" : "signedOut");
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const applyTokens = useCallback(async (tokens: TokenPair) => {
    // Refuse an incomplete pair outright: persisting it would create a
    // "signedIn with no session" state (seen live when register returned a
    // 2xx without tokens). Message carries no token values (FR-018).
    if (
      typeof tokens.accessToken !== "string" ||
      tokens.accessToken.length === 0 ||
      typeof tokens.refreshToken !== "string" ||
      tokens.refreshToken.length === 0
    ) {
      throw new Error("applyTokens: refusing to persist an incomplete token pair");
    }
    await saveTokens(tokens); // persist first — see invariant above
    tokensRef.current = tokens;
    setStatus("signedIn");
  }, []);

  const signIn = useCallback(
    async (email: string, password: string): Promise<ApiError | null> => {
      const result = await login({ email, password });
      if (!result.ok) {
        return result.error;
      }
      await applyTokens({
        accessToken: result.data.accessToken,
        refreshToken: result.data.refreshToken,
      });
      return null;
    },
    [applyTokens],
  );

  const signOut = useCallback(async () => {
    await clearTokens(); // clear storage first — see invariant above
    tokensRef.current = null;
    setStatus("signedOut");
  }, []);

  const getTokens = useCallback(() => tokensRef.current, []);

  const value = useMemo(
    () => ({ status, signIn, signOut, getTokens, applyTokens }),
    [status, signIn, signOut, getTokens, applyTokens],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used inside <AuthProvider>");
  }
  return ctx;
}
