/**
 * Switches between the auth stack and the main tabs on AuthContext status.
 * The two branches are mutually exclusive screens in one navigator, so a
 * sign-in/sign-out transition tears down the other branch entirely — no
 * back-navigation into a stale session (data-model.md lifecycle).
 */
import { createNativeStackNavigator } from "@react-navigation/native-stack";

import { useAuth } from "../auth/AuthContext";
import LoginScreen from "../screens/LoginScreen";
import RegisterScreen from "../screens/RegisterScreen";
import MainTabs from "./MainTabs";

export type RootParamList = {
  Login: undefined;
  Register: undefined;
  Main: undefined;
};

const Stack = createNativeStackNavigator<RootParamList>();

export default function RootNavigator() {
  const { status } = useAuth();

  // Render nothing until the secure store has been read: flashing the login
  // form at an already-signed-in user (or vice versa) is worse than a blank
  // frame during a sub-second bootstrap.
  if (status === "bootstrapping") {
    return null;
  }

  return (
    <Stack.Navigator screenOptions={{ headerShown: false }}>
      {status === "signedIn" ? (
        <Stack.Screen name="Main" component={MainTabs} />
      ) : (
        // Screens take plain callbacks (not navigation props) so they stay
        // testable without a navigator; the wiring lives here.
        <>
          <Stack.Screen name="Login">
            {({ navigation }) => (
              <LoginScreen onRegisterPress={() => navigation.navigate("Register")} />
            )}
          </Stack.Screen>
          <Stack.Screen name="Register">
            {({ navigation }) => (
              <RegisterScreen onSignInPress={() => navigation.navigate("Login")} />
            )}
          </Stack.Screen>
        </>
      )}
    </Stack.Navigator>
  );
}
