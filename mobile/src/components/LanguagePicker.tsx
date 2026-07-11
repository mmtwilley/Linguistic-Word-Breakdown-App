/**
 * Source-language override (FR-006a). Controlled: HomeScreen owns the value
 * so it persists across submissions (T020) and re-auth (T025 draft stash).
 *
 * `null` = auto-detect and sends no `language` field on the wire — the
 * absence of a hint, not a fifth hint value (data-model.md validation rules).
 */
import { Pressable, StyleSheet, Text, View } from "react-native";

import type { LanguageHint } from "../api/types";

export interface LanguagePickerProps {
  value: LanguageHint | null;
  onChange: (value: LanguageHint | null) => void;
  disabled?: boolean;
}

const OPTIONS: { hint: LanguageHint | null; label: string }[] = [
  { hint: null, label: "auto" },
  { hint: "kor", label: "한국어" },
  { hint: "jpn", label: "日本語" },
  { hint: "cmn", label: "中文" },
  { hint: "lat", label: "English/Latin" },
];

export default function LanguagePicker({ value, onChange, disabled }: LanguagePickerProps) {
  return (
    <View style={styles.row} accessibilityRole="radiogroup" accessibilityLabel="Language">
      {OPTIONS.map(({ hint, label }) => {
        const selected = hint === value;
        return (
          <Pressable
            key={label}
            style={[styles.chip, selected && styles.chipSelected]}
            onPress={() => onChange(hint)}
            disabled={disabled}
            accessibilityRole="radio"
            accessibilityLabel={label}
            accessibilityState={{ selected, disabled: disabled === true }}
          >
            <Text style={[styles.chipText, selected && styles.chipTextSelected]}>{label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 8,
  },
  chip: {
    borderWidth: 1,
    borderColor: "#cccccc",
    borderRadius: 16,
    paddingHorizontal: 12,
    paddingVertical: 6,
    backgroundColor: "#ffffff",
  },
  chipSelected: {
    backgroundColor: "#2563eb",
    borderColor: "#2563eb",
  },
  chipText: {
    fontSize: 14,
    color: "#333333",
  },
  chipTextSelected: {
    color: "#ffffff",
    fontWeight: "600",
  },
});
