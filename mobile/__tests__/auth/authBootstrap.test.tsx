/**
 * Session restore on app launch (T027 ← T010, data-model.md lifecycle):
 * bootstrapping resolves to signedIn when a stored pair exists, signedOut
 * when absent, and corrupt entries self-purge to signedOut.
 */
import { renderHook, waitFor } from "@testing-library/react-native";
import type { ReactNode } from "react";

import { AuthProvider, useAuth } from "../../src/auth/AuthContext";

jest.mock("expo-secure-store", () => ({
  getItemAsync: jest.fn(),
  setItemAsync: jest.fn().mockResolvedValue(undefined),
  deleteItemAsync: jest.fn().mockResolvedValue(undefined),
}));
// eslint-disable-next-line import/first
import * as SecureStore from "expo-secure-store";
const secureStore = SecureStore as jest.Mocked<typeof SecureStore>;

function wrapper({ children }: { children: ReactNode }) {
  return <AuthProvider>{children}</AuthProvider>;
}

beforeEach(() => {
  secureStore.getItemAsync.mockReset();
  secureStore.deleteItemAsync.mockClear();
});

describe("AuthContext bootstrap", () => {
  it("restores signedIn from a stored token pair", async () => {
    secureStore.getItemAsync.mockResolvedValue(
      JSON.stringify({ accessToken: "tok", refreshToken: "ref" }),
    );

    // Awaiting render flushes the bootstrap effect, so the transient
    // "bootstrapping" state is already resolved here — assert the outcome.
    const { result } = await renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.status).toBe("signedIn"));
    expect(result.current.getTokens()).toEqual({ accessToken: "tok", refreshToken: "ref" });
  });

  it("lands signedOut when nothing is stored", async () => {
    secureStore.getItemAsync.mockResolvedValue(null);

    const { result } = await renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.status).toBe("signedOut"));
    expect(result.current.getTokens()).toBeNull();
  });

  it("purges a corrupt entry and lands signedOut", async () => {
    secureStore.getItemAsync.mockResolvedValue("not-json{");

    const { result } = await renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.status).toBe("signedOut"));
    expect(secureStore.deleteItemAsync).toHaveBeenCalledWith("lingua.auth.tokens");
  });
});
