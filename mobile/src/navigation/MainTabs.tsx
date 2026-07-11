/**
 * Signed-in tab shell: Home | Flashcards | History | Settings.
 * All four are placeholders for now — T020 swaps in the real Home screen,
 * T033 adds icons. Named wrapper components (not inline closures) so React
 * Navigation doesn't remount the screen on every render.
 */
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";

import PlaceholderScreen from "../screens/PlaceholderScreen";

export type MainTabsParamList = {
  Home: undefined;
  Flashcards: undefined;
  History: undefined;
  Settings: undefined;
};

const Tabs = createBottomTabNavigator<MainTabsParamList>();

function HomeTab() {
  return <PlaceholderScreen title="Home" />;
}

function FlashcardsTab() {
  return <PlaceholderScreen title="Flashcards" />;
}

function HistoryTab() {
  return <PlaceholderScreen title="History" />;
}

function SettingsTab() {
  return <PlaceholderScreen title="Settings" />;
}

export default function MainTabs() {
  return (
    <Tabs.Navigator initialRouteName="Home">
      <Tabs.Screen name="Home" component={HomeTab} />
      <Tabs.Screen name="Flashcards" component={FlashcardsTab} />
      <Tabs.Screen name="History" component={HistoryTab} />
      <Tabs.Screen name="Settings" component={SettingsTab} />
    </Tabs.Navigator>
  );
}
