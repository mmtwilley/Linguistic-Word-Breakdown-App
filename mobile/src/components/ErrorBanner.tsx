/**
 * Banner for request failures (FR-013/FR-014). Renders the plain-language
 * message already mapped by the caller (messages.ts) and offers Retry only
 * when the envelope says retrying might help.
 */
import { Pressable, StyleSheet, Text, View } from "react-native";

export interface ErrorBannerProps {
  /** Display-ready text — callers map codes via messages.ts first. */
  message: string;
  /** From ApiError.retryable. */
  retryable: boolean;
  onRetry: () => void;
}

export default function ErrorBanner({ message, retryable, onRetry }: ErrorBannerProps) {
  return (
    <View style={styles.banner} accessibilityRole="alert">
      <Text style={styles.message}>{message}</Text>
      {retryable && (
        <Pressable style={styles.retry} onPress={onRetry} accessibilityRole="button">
          <Text style={styles.retryText}>Retry</Text>
        </Pressable>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  banner: {
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
    backgroundColor: "#fdecea",
    borderRadius: 8,
    padding: 12,
  },
  message: {
    flex: 1,
    color: "#b00020",
    fontSize: 14,
  },
  retry: {
    borderWidth: 1,
    borderColor: "#b00020",
    borderRadius: 6,
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  retryText: {
    color: "#b00020",
    fontSize: 14,
    fontWeight: "600",
  },
});
