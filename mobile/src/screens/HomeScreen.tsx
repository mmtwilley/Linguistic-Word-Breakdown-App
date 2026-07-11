/**
 * Analyze screen (US1). Request state machine (data-model.md):
 *
 *   idle → submitting → success (result replaces previous)
 *                     → failed  (error routed per code, T021)
 *
 * The language override lives in screen state, defaults to auto, and
 * persists across submissions (FR-006a). An in-flight request is aborted
 * when the screen loses focus or unmounts; aborted requests reset to idle
 * rather than surfacing a fake network error.
 */
import { useFocusEffect } from "@react-navigation/native";
import { useCallback, useRef, useState } from "react";
import { ScrollView, StyleSheet, Text, View } from "react-native";

import { analyze } from "../api/analyze";
import type { ApiError } from "../api/client";
import type { AnalysisRequest, AnalysisResult, LanguageHint } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import AnalyzeInput from "../components/AnalyzeInput";
import ErrorBanner from "../components/ErrorBanner";
import LanguagePicker from "../components/LanguagePicker";
import ResultView from "../components/ResultView";
import { errorMessage } from "../i18n/messages";

type RequestState =
  | { phase: "idle" }
  | { phase: "submitting" }
  | { phase: "success"; result: AnalysisResult }
  | { phase: "failed"; error: ApiError };

/** Where each failure surfaces (FR-013, contract §4): at the input, by the picker, or in the banner. */
function errorPlacement(code: string): "input" | "picker" | "banner" {
  if (code === "INVALID_INPUT" || code === "VALIDATION_ERROR") {
    return "input";
  }
  if (code === "LANGUAGE_UNDETECTABLE") {
    return "picker";
  }
  return "banner";
}

export default function HomeScreen() {
  const { getTokens } = useAuth();
  const [text, setText] = useState("");
  const [language, setLanguage] = useState<LanguageHint | null>(null);
  const [request, setRequest] = useState<RequestState>({ phase: "idle" });
  const abortRef = useRef<AbortController | null>(null);

  // Abort any in-flight request when the tab loses focus or unmounts.
  useFocusEffect(
    useCallback(() => {
      return () => {
        abortRef.current?.abort();
        abortRef.current = null;
      };
    }, []),
  );

  const handleSubmit = useCallback(async () => {
    const accessToken = getTokens()?.accessToken;
    if (!accessToken) {
      // Can't happen while signedIn (AuthContext invariant); treated as an
      // expired session rather than crashing if it ever does.
      setRequest({
        phase: "failed",
        error: { code: "UNAUTHORIZED", message: "", retryable: false },
      });
      return;
    }

    const body: AnalysisRequest = { text: text.trim() };
    if (language !== null) {
      body.language = language; // auto = omit the field entirely (FR-006a)
    }

    const controller = new AbortController();
    abortRef.current = controller;
    setRequest({ phase: "submitting" });

    const result = await analyze(body, accessToken, controller.signal);

    if (controller.signal.aborted) {
      // Blur/unmount cancelled us — discard, don't show a fake failure.
      setRequest({ phase: "idle" });
      return;
    }
    abortRef.current = null;
    setRequest(result.ok ? { phase: "success", result: result.data } : { phase: "failed", error: result.error });
  }, [getTokens, text, language]);

  const failure = request.phase === "failed" ? request.error : null;
  const placement = failure ? errorPlacement(failure.code) : null;
  const failureText = failure ? errorMessage(failure.code, failure.message) : null;

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.content}>
      <AnalyzeInput
        value={text}
        onChangeText={setText}
        onSubmit={handleSubmit}
        submitting={request.phase === "submitting"}
        errorText={placement === "input" ? failureText : null}
      />
      <LanguagePicker
        value={language}
        onChange={setLanguage}
        disabled={request.phase === "submitting"}
      />
      {placement === "picker" && (
        <Text style={styles.pickerHint} accessibilityRole="alert">
          {failureText}
        </Text>
      )}
      {placement === "banner" && failure && (
        <ErrorBanner
          message={failureText ?? ""}
          retryable={failure.retryable}
          onRetry={handleSubmit}
        />
      )}
      <View style={styles.results}>
        <ResultView
          loading={request.phase === "submitting"}
          result={request.phase === "success" ? request.result : null}
        />
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: "#f8f8f8",
  },
  content: {
    padding: 16,
    gap: 12,
  },
  pickerHint: {
    color: "#b00020",
    fontSize: 14,
  },
  results: {
    marginTop: 4,
  },
});
