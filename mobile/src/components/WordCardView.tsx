/**
 * One word card (US1-AS3). Null fields are omitted entirely — no empty rows,
 * no "—" placeholders. `ipa` is deliberately not rendered this phase.
 * T029 extends this with per-card reliability warnings.
 */
import { StyleSheet, Text, View } from "react-native";

import type { WordCard } from "../api/types";

export interface WordCardViewProps {
  card: WordCard;
}

export default function WordCardView({ card }: WordCardViewProps) {
  const { surface, lemma, pos, gloss, romanization } = card;
  // Showing "오늘 → 오늘" is noise; the lemma earns a row only when it differs.
  const lemmaToShow = lemma !== null && lemma !== surface ? lemma : null;

  return (
    <View style={styles.card}>
      <View style={styles.headerRow}>
        <Text style={styles.surface}>{surface}</Text>
        {romanization !== null && <Text style={styles.romanization}>{romanization}</Text>}
        {pos !== null && <Text style={styles.pos}>{pos}</Text>}
      </View>
      {gloss !== null && <Text style={styles.gloss}>{gloss}</Text>}
      {lemmaToShow !== null && <Text style={styles.lemma}>base form: {lemmaToShow}</Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    borderWidth: 1,
    borderColor: "#e5e5e5",
    borderRadius: 8,
    padding: 12,
    gap: 4,
    backgroundColor: "#ffffff",
  },
  headerRow: {
    flexDirection: "row",
    alignItems: "baseline",
    gap: 8,
    flexWrap: "wrap",
  },
  surface: {
    fontSize: 18,
    fontWeight: "600",
  },
  romanization: {
    fontSize: 14,
    color: "#666666",
    fontStyle: "italic",
  },
  pos: {
    fontSize: 12,
    color: "#2563eb",
    backgroundColor: "#eff4ff",
    borderRadius: 4,
    paddingHorizontal: 6,
    paddingVertical: 1,
    overflow: "hidden",
  },
  gloss: {
    fontSize: 15,
    color: "#333333",
  },
  lemma: {
    fontSize: 13,
    color: "#666666",
  },
});
