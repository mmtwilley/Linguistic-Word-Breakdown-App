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

  it("success registers, persists the pair, and signs the user in (US2-AS1)", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, { accessToken: "newTok", refreshToken: "newRef", expiresIn: 900 }),
    );
    await renderRegister();
    await fillAndSubmit("new@example.com", "longenough1");

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://localhost:8080/api/auth/register");
    expect(JSON.parse(init.body)).toEqual({ email: "new@example.com", password: "longenough1" });

    // applyTokens persisted the pair — the signedIn flip (and Home landing)
    // follows from this via the root navigator.
    expect(secureStore.setItemAsync).toHaveBeenCalledWith(
      "lingua.auth.tokens",
      JSON.stringify({ accessToken: "newTok", refreshToken: "newRef" }),
    );
  });
});
