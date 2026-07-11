/**
 * Switches between the auth stack and the main tabs on AuthContext status.
 * The two branches are mutually exclusive screens in one navigator, so a
 * sign-in/sign-out transition tears down the other branch entirely — no
 * back-navigation into a stale session (data-model.md lifecycle).
 */
import { createNativeStackNavigator } from "@react-navigation/native-stack";

import { useAuth } from "../auth/AuthContext";
import LoginScreen from "../screens/LoginScreen";
import MainTabs from "./MainTabs";

export type RootParamList = {
  Login: undefined;
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
        <Stack.Screen name="Login" component={LoginScreen} />
      )}
    </Stack.Navigator>
  );
}
