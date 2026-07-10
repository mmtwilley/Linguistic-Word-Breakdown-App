import { render, screen } from "@testing-library/react-native";

import App from "../App";

describe("App smoke test", () => {
  it("renders the template screen", async () => {
    await render(<App />);
    expect(
      screen.getByText("Open up App.tsx to start working on your app!"),
    ).toBeOnTheScreen();
  });
});
