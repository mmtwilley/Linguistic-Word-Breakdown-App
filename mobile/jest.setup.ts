// SafeAreaProvider renders nothing until the native module reports window
// metrics, which never happens under Jest — use the library's shipped mock.
jest.mock("react-native-safe-area-context", () => {
  const mock = require("react-native-safe-area-context/jest/mock");
  return mock.default ?? mock;
});
