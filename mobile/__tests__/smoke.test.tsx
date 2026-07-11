import { render, screen } from "@testing-library/react-native";

import App from "../App";

// Empty secure store: the app must bootstrap to signed-out and show Login.
jest.mock("expo-secure-store", () => ({
  getItemAsync: jest.fn().mockResolvedValue(null),
  setItemAsync: jest.fn().mockResolvedValue(undefined),
  deleteItemAsync: jest.fn().mockResolvedValue(undefined),
}));

describe("App smoke test", () => {
  it("boots to the login screen when no session is stored", async () => {
    await render(<App />);
    expect(await screen.findByRole("button", { name: "Sign in" })).toBeOnTheScreen();
  });
});
