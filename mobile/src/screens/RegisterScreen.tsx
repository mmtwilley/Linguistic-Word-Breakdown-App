/**
 * Registration screen (T023, US2-AS1).
 *
 * Client-side validation mirrors contract §1 (valid email, password 8–128)
 * so obvious mistakes never cost a round trip; the backend remains the
 * authority and its VALIDATION_ERROR still renders inline if they disagree.
 * On success the new token pair goes through AuthContext.applyTokens, which
 * flips status to "signedIn" — the root navigator then swaps to the main
 * tabs, landing on Home. EMAIL_ALREADY_EXISTS gets a dedicated "sign in
 * instead" affordance per contract §4.
 */
import { useState } from "react";
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";

import { login, register } from "../api/auth";
import { useAuth } from "../auth/AuthContext";
import { errorMessage } from "../i18n/messages";

export interface RegisterScreenProps {
  /** Navigates back to the sign-in screen. */
  onSignInPress?: () => void;
}

const PASSWORD_MIN = 8;
const PASSWORD_MAX = 128;
// Coarse shape check only — the backend's validator is the authority.
const EMAIL_PATTERN = /^\S+@\S+\.\S+$/;

export function validateEmail(email: string): string | null {
  return EMAIL_PATTERN.test(email.trim()) ? null : "Enter a valid email address.";
}

export function validatePassword(password: string): string | null {
  if (password.length < PASSWORD_MIN || password.length > PASSWORD_MAX) {
    return `Password must be ${PASSWORD_MIN}–${PASSWORD_MAX} characters.`;
  }
  return null;
}

export default function RegisterScreen({ onSignInPress }: RegisterScreenProps) {
  const { applyTokens } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [emailError, setEmailError] = useState<string | null>(null);
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const [emailTaken, setEmailTaken] = useState(false);

  const canSubmit = email.trim().length > 0 && password.length > 0 && !submitting;

  const handleSubmit = async () => {
    if (!canSubmit) {
      return;
    }
    const emailIssue = validateEmail(email);
    const passwordIssue = validatePassword(password);
    setEmailError(emailIssue);
    setPasswordError(passwordIssue);
    if (emailIssue || passwordIssue) {
      return;
    }

    setSubmitting(true);
    setServerError(null);
    setEmailTaken(false);
    const trimmedEmail = email.trim();
    const result = await register({ email: trimmedEmail, password });
    if (!result.ok) {
      setServerError(errorMessage(result.error.code, result.error.message));
      setEmailTaken(result.error.code === "EMAIL_ALREADY_EXISTS");
      setSubmitting(false);
      return;
    }

    // The backend's register returns 201 + a message WITHOUT tokens (verified
    // live 2026-07-11, contract §1 corrected) — only login issues a token
    // pair, so a follow-up login establishes the session.
    const session = await login({ email: trimmedEmail, password });
    if (!session.ok) {
      // Account exists but auto sign-in failed (e.g. network blip): send the
      // user to the sign-in screen rather than a dead end.
      setServerError(errorMessage(session.error.code, session.error.message));
      setEmailTaken(true); // reuse the "Sign in instead" affordance
      setSubmitting(false);
      return;
    }

    // Persists the pair and flips status to "signedIn"; the root navigator
    // unmounts this screen and shows the main tabs (Home).
    await applyTokens({
      accessToken: session.data.accessToken,
      refreshToken: session.data.refreshToken,
    });
  };

  return (
    <KeyboardAvoidingView
      style={styles.container}
      behavior={Platform.OS === "ios" ? "padding" : undefined}
    >
      <View style={styles.form}>
        <Text style={styles.title}>Create account</Text>

        <TextInput
          style={styles.input}
          placeholder="Email"
          value={email}
          onChangeText={(text) => {
            setEmail(text);
            setEmailError(null);
          }}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="email-address"
          autoComplete="email"
          editable={!submitting}
          accessibilityLabel="Email"
        />
        {emailError !== null && (
          <Text style={styles.error} accessibilityRole="alert">
            {emailError}
          </Text>
        )}

        <TextInput
          style={styles.input}
          placeholder="Password (8–128 characters)"
          value={password}
          onChangeText={(text) => {
            setPassword(text);
            setPasswordError(null);
          }}
          secureTextEntry
          autoCapitalize="none"
          autoComplete="password-new"
          editable={!submitting}
          accessibilityLabel="Password"
          onSubmitEditing={handleSubmit}
        />
        {passwordError !== null && (
          <Text style={styles.error} accessibilityRole="alert">
            {passwordError}
          </Text>
        )}

        {serverError !== null && (
          <Text style={styles.error} accessibilityRole="alert">
            {serverError}
          </Text>
        )}

        <Pressable
          style={[styles.button, !canSubmit && styles.buttonDisabled]}
          onPress={handleSubmit}
          disabled={!canSubmit}
          accessibilityRole="button"
          accessibilityLabel="Create account"
        >
          {submitting ? (
            <ActivityIndicator color="#ffffff" />
          ) : (
            <Text style={styles.buttonText}>Create account</Text>
          )}
        </Pressable>

        <Pressable onPress={onSignInPress} accessibilityRole="link">
          <Text style={styles.signInLink}>
            {emailTaken ? "Sign in instead" : "Already have an account? Sign in"}
          </Text>
        </Pressable>
      </View>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: "center",
    backgroundColor: "#ffffff",
  },
  form: {
    paddingHorizontal: 24,
    gap: 12,
  },
  title: {
    fontSize: 28,
    fontWeight: "600",
    marginBottom: 12,
    textAlign: "center",
  },
  input: {
    borderWidth: 1,
    borderColor: "#cccccc",
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 16,
  },
  error: {
    color: "#b00020",
    fontSize: 14,
  },
  button: {
    backgroundColor: "#2563eb",
    borderRadius: 8,
    paddingVertical: 12,
    alignItems: "center",
  },
  buttonDisabled: {
    backgroundColor: "#93b4f5",
  },
  buttonText: {
    color: "#ffffff",
    fontSize: 16,
    fontWeight: "600",
  },
  signInLink: {
    color: "#2563eb",
    textAlign: "center",
    paddingVertical: 8,
  },
});
