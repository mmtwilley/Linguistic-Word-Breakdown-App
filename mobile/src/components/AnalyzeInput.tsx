/**
 * Text entry for the analyze screen (FR-015, FR-012).
 *
 * Controlled by the parent: HomeScreen owns the text (it must survive
 * re-auth via the draft stash, T025). Deliberately no maxLength — pasting
 * more than 500 chars must be possible so the user can see the overage on
 * the counter and trim, rather than having the paste silently truncated.
 */
import { Pressable, StyleSheet, Text, TextInput, View } from "react-native";

/** Backend contract: @Size(max = 500) on AnalysisRequest.text. */
export const MAX_TEXT_LENGTH = 500;

export interface AnalyzeInputProps {
  value: string;
  onChangeText: (text: string) => void;
  onSubmit: () => void;
  /** Request in flight — submission stays disabled (FR-012). */
  submitting: boolean;
  /** Inline error shown under the field (INVALID_INPUT / VALIDATION_ERROR, T021). */
  errorText?: string | null;
}

export function canSubmitText(value: string, submitting: boolean): boolean {
  const length = value.length;
  return !submitting && value.trim().length > 0 && length <= MAX_TEXT_LENGTH;
}

export default function AnalyzeInput({
  value,
  onChangeText,
  onSubmit,
  submitting,
  errorText,
}: AnalyzeInputProps) {
  const overLimit = value.length > MAX_TEXT_LENGTH;
  const canSubmit = canSubmitText(value, submitting);

  return (
    <View style={styles.container}>
      <TextInput
        style={styles.input}
        value={value}
        onChangeText={onChangeText}
        multiline
        placeholder="Enter or paste text to analyze…"
        autoCapitalize="none"
        autoCorrect={false}
        editable={!submitting}
        accessibilityLabel="Text to analyze"
      />
      <View style={styles.row}>
        <Text
          style={[styles.counter, overLimit && styles.counterOver]}
          accessibilityLabel={`${value.length} of ${MAX_TEXT_LENGTH} characters`}
        >
          {value.length}/{MAX_TEXT_LENGTH}
        </Text>
        <Pressable
          style={[styles.submit, !canSubmit && styles.submitDisabled]}
          onPress={onSubmit}
          disabled={!canSubmit}
          accessibilityRole="button"
          accessibilityLabel="Analyze"
        >
          <Text style={styles.submitText}>Analyze</Text>
        </Pressable>
      </View>
      {errorText != null && (
        <Text style={styles.error} accessibilityRole="alert">
          {errorText}
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: 8,
  },
  input: {
    borderWidth: 1,
    borderColor: "#cccccc",
    borderRadius: 8,
    padding: 12,
    fontSize: 16,
    minHeight: 96,
    textAlignVertical: "top",
  },
  row: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
  },
  counter: {
    fontSize: 13,
    color: "#666666",
  },
  counterOver: {
    color: "#b00020",
    fontWeight: "600",
  },
  submit: {
    backgroundColor: "#2563eb",
    paddingHorizontal: 20,
    paddingVertical: 10,
    borderRadius: 8,
  },
  submitDisabled: {
    backgroundColor: "#93b4f5",
  },
  submitText: {
    color: "#ffffff",
    fontSize: 16,
    fontWeight: "600",
  },
  error: {
    color: "#b00020",
    fontSize: 14,
  },
});
