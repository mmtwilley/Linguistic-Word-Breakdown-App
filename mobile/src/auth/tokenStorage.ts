/**
 * Secure persistence for the session token pair (FR-002).
 *
 * Backed by expo-secure-store (iOS Keychain / Android Keystore) — never
 * AsyncStorage, which is plaintext. Both tokens live under a single key as
 * one JSON value, so the pair is written and cleared atomically: a rotated
 * refresh token can never be persisted without its matching access token
 * (data-model.md Session invariants).
 *
 * Token values must never be logged (FR-018).
 */
import * as SecureStore from "expo-secure-store";

const TOKENS_KEY = "lingua.auth.tokens";

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
}

export async function saveTokens(tokens: TokenPair): Promise<void> {
  await SecureStore.setItemAsync(TOKENS_KEY, JSON.stringify(tokens));
}

/** Returns the stored pair, or null when absent or unreadable (treat as signed out). */
export async function loadTokens(): Promise<TokenPair | null> {
  let raw: string | null;
  try {
    raw = await SecureStore.getItemAsync(TOKENS_KEY);
  } catch {
    // Keystore can throw after backup restores or OS credential resets —
    // a corrupt entry is indistinguishable from signed out.
    return null;
  }
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as Partial<TokenPair>;
    if (typeof parsed.accessToken === "string" && typeof parsed.refreshToken === "string") {
      return { accessToken: parsed.accessToken, refreshToken: parsed.refreshToken };
    }
  } catch {
    // fall through to clear
  }
  // Unparseable or missing fields: purge the corrupt entry.
  await clearTokens();
  return null;
}

export async function clearTokens(): Promise<void> {
  await SecureStore.deleteItemAsync(TOKENS_KEY);
}
