/**
 * Analysis result display (US1). Words render in array order — the backend
 * order IS the sentence order; never sort client-side (FR-008). A new result
 * replaces the previous one wholesale (US1-AS4).
 *
 * T029 adds issue routing (result notices + card flags); T030 adds the
 * empty-analysis explanation.
 */
import { ActivityIndicator, StyleSheet, Text, View } from "react-native";

import type { AnalysisResult } from "../api/types";
import WordCardView from "./WordCardView";

export interface ResultViewProps {
  /** Request in flight — shows the loading state instead of any result. */
  loading: boolean;
  /** Most recent successful analysis; null before the first one. */
  result: AnalysisResult | null;
}

export default function ResultView({ loading, result }: ResultViewProps) {
  if (loading) {
    return (
      <View style={styles.loading} accessibilityLabel="Analyzing">
        <ActivityIndicator size="large" color="#2563eb" />
        <Text style={styles.loadingText}>Analyzing…</Text>
      </View>
    );
  }
  if (result === null) {
    return null;
  }

  return (
    <View style={styles.container}>
      {result.translation !== null && (
        <Text style={styles.translation} accessibilityRole="header">
          {result.translation}
        </Text>
      )}
      <View style={styles.cards}>
        {/* Index keys are safe here: the list is only ever replaced whole,
            never reordered or edited in place. Duplicate surfaces (は, です)
            make surface strings unusable as keys. */}
        {result.words.map((card, index) => (
          <WordCardView key={index} card={card} />
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: 12,
  },
  loading: {
    alignItems: "center",
    paddingVertical: 32,
    gap: 8,
  },
  loadingText: {
    color: "#666666",
    fontSize: 14,
  },
  translation: {
    fontSize: 18,
    fontWeight: "600",
  },
  cards: {
    gap: 8,
  },
});
