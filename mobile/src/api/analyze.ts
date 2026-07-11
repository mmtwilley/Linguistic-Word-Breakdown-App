/**
 * Analyze endpoint (contract §2). Requires a Bearer token — in Phase 3 the
 * screen passes it from AuthContext.getTokens(); T024 swaps callers over to
 * useAuthedFetch for automatic 401 refresh.
 */
import { request, type ApiResult } from "./client";
import type { AnalysisRequest, AnalysisResult } from "./types";

export function analyze(
  body: AnalysisRequest,
  accessToken: string,
  signal?: AbortSignal,
): Promise<ApiResult<AnalysisResult>> {
  return request<AnalysisResult>("/api/analyze", { method: "POST", body, accessToken, signal });
}
