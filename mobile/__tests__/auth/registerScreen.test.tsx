/**
 * RegisterScreen (T027 ← T023, US2-AS1): client-side validation mirrors
 * contract §1 without a network call, EMAIL_ALREADY_EXISTS offers sign-in,
 * and success persists the pair (which flips the session to signedIn).
 */
import { fireEvent, render, screen } from "@testing-library/react-native";

import { AuthProvider } from "../../src/auth/AuthContext";
import RegisterScreen from "../../src/screens/RegisterScreen";

process.env.EXPO_PUBLIC_API_URL = "http://localhost:8080";

jest.mock("expo-secure-store", () => ({
  getItemAsync: jest.fn().mockResolvedValue(null), // start signed out
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

const onSignInPress = jest.fn();

async function renderRegister() {
  await render(
    <AuthProvider>
      <RegisterScreen onSignInPress={onSignInPress} />
    </AuthProvider>,
  );
  await screen.findByRole("button", { name: "Create account" });
}

async function fillAndSubmit(email: string, password: string) {
  await fireEvent.changeText(screen.getByLabelText("Email"), email);
  await fireEvent.changeText(screen.getByLabelText("Password"), password);
  await fireEvent.press(screen.getByRole("button", { name: "Create account" }));
}

beforeEach(() => {
  fetchMock.mockReset();
  secureStore.setItemAsync.mockClear();
  onSignInPress.mockClear();
});

describe("RegisterScreen validation (contract §1)", () => {
  it("rejects a malformed email inline without calling the server", async () => {
    await renderRegister();
    await fillAndSubmit("not-an-email", "longenough1");

    expect(await screen.findByText("Enter a valid email address.")).toBeOnTheScreen();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects a password under 8 chars inline without calling the server", async () => {
    await renderRegister();
    await fillAndSubmit("user@example.com", "short");

    expect(await screen.findByText("Password must be 8–128 characters.")).toBeOnTheScreen();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects a password over 128 chars", async () => {
    await renderRegister();
    await fillAndSubmit("user@example.com", "x".repeat(129));

    expect(await screen.findByText("Password must be 8–128 characters.")).toBeOnTheScreen();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("RegisterScreen server outcomes", () => {
  it("EMAIL_ALREADY_EXISTS shows the message and a 'Sign in instead' affordance", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(409, {
        error: { code: "EMAIL_ALREADY_EXISTS", message: "Taken.", retryable: false },
      }),
    );
    await renderRegister();
    await fillAndSubmit("taken@example.com", "longenough1");

    expect(await screen.findByText(/already exists/)).toBeOnTheScreen();

    await fireEvent.press(screen.getByText("Sign in instead"));
    expect(onSignInPress).toHaveBeenCalled();
  });

  it("success registers (201, no tokens), auto-logs-in, and persists the pair (US2-AS1)", async () => {
    // Real backend behavior (contract §1 correction 2026-07-11): register
    // returns 201 + message WITHOUT tokens; only login issues the pair.
    fetchMock.mockImplementation(async (url: string) => {
      if (url.endsWith("/api/auth/register")) {
        return jsonResponse(201, { message: "Account created successfully." });
      }
      return jsonResponse(200, { accessToken: "newTok", refreshToken: "newRef", expiresIn: 900 });
    });
    await renderRegister();
    await fillAndSubmit("new@example.com", "longenough1");

    const urls = fetchMock.mock.calls.map(([url]) => url);
    expect(urls).toEqual([
      "http://localhost:8080/api/auth/register",
      "http://localhost:8080/api/auth/login",
    ]);
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({
      email: "new@example.com",
      password: "longenough1",
    });

    // applyTokens persisted the pair — the signedIn flip (and Home landing)
    // follows from this via the root navigator.
    expect(secureStore.setItemAsync).toHaveBeenCalledWith(
      "lingua.auth.tokens",
      JSON.stringify({ accessToken: "newTok", refreshToken: "newRef" }),
    );
  });

  it("register succeeds but auto-login fails → error + sign-in affordance, nothing persisted", async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (url.endsWith("/api/auth/register")) {
        return jsonResponse(201, { message: "Account created successfully." });
      }
      throw new TypeError("Network request failed");
    });
    await renderRegister();
    await fillAndSubmit("new@example.com", "longenough1");

    expect(await screen.findByText(/Check your connection/)).toBeOnTheScreen();
    expect(screen.getByText("Sign in instead")).toBeOnTheScreen();
    expect(secureStore.setItemAsync).not.toHaveBeenCalled();
  });
});
