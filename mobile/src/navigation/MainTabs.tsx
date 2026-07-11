/**
 * Signed-in tab shell: Home | Flashcards | History | Settings.
 * All four are placeholders for now — T020 swaps in the real Home screen,
 * T033 adds icons. Named wrapper components (not inline closures) so React
 * Navigation doesn't remount the screen on every render.
 */
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { Pressable, StyleSheet, Text } from "react-native";

import { useAuth } from "../auth/AuthContext";
import { clearDraft } from "../auth/draftStash";
import HomeScreen from "../screens/HomeScreen";
import PlaceholderScreen from "../screens/PlaceholderScreen";

export type MainTabsParamList = {
  Home: undefined;
  Flashcards: undefined;
  History: undefined;
  Settings: undefined;
};

const Tabs = createBottomTabNavigator<MainTabsParamList>();

function FlashcardsTab() {
  return <PlaceholderScreen title="Flashcards" />;
}

function HistoryTab() {
  return <PlaceholderScreen title="History" />;
}

function SettingsTab() {
  return <PlaceholderScreen title="Settings" />;
}

/**
 * Explicit sign-out (FR-005): discards the draft FIRST (FR-004 — this is a
 * user choice, unlike the refresh-failure path which preserves it), then
 * clears tokens; the root navigator falls back to Login on the status flip.
 */
function SignOutButton() {
  const { signOut } = useAuth();
  const handlePress = () => {
    clearDraft();
    void signOut();
  };
  return (
    <Pressable
      onPress={handlePress}
      accessibilityRole="button"
      accessibilityLabel="Sign out"
      hitSlop={8}
    >
      <Text style={styles.signOut}>Sign out</Text>
    </Pressable>
  );
}

function renderSignOutButton() {
  return <SignOutButton />;
}

export default function MainTabs() {
  return (
    <Tabs.Navigator initialRouteName="Home">
      <Tabs.Screen
        name="Home"
        component={HomeScreen}
        options={{ headerRight: renderSignOutButton }}
      />
      <Tabs.Screen name="Flashcards" component={FlashcardsTab} />
      <Tabs.Screen name="History" component={HistoryTab} />
      <Tabs.Screen name="Settings" component={SettingsTab} />
    </Tabs.Navigator>
  );
}

const styles = StyleSheet.create({
  signOut: {
    color: "#2563eb",
    fontSize: 15,
    fontWeight: "500",
    paddingHorizontal: 16,
  },
});
