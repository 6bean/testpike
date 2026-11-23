package com.sixbean.testpike.aiservice.mock.api.dto;

import java.util.List;
import java.util.Map;

/**
 * A-3 sync generation request ; core orchestrator → ai-service
 * internal door). Carries everything the drafting context needs as data: the
 * core control plane owns the contract schema and the recorded few-shot
 * samples, ai-service never reaches into mock tables (data-plane zero-write
 * posture, keys stay here).
 *
 * @param spaceId serving space row id (cache is space-scoped)
 * @param projectId LLM routing anchor (project endpoint config wins)
 * @param method HTTP method of the unmatched request
 * @param path normalized endpoint path (template segments kept)
 * @param maskedFingerprint digest of the masked request (PII never travels)
 * @param contractSchemaJson endpoint contract schema as text, may be null —
 * the model is told to obey it when present
 * @param fewShotSamples recorded request/response pairs for the same
 * endpoint family (masking already applied upstream
 * by the recording leg; empty list = no examples)
 * @param sessionSummary plain lines of the lane's recent real exchanges
 * ; the core control plane owns the read,
 * ai-service only places it in the prompt), may be null
 */
public record MockLlmFallbackGenerateRequest(
        Long spaceId,
        Long projectId,
        String method,
        String path,
        String maskedFingerprint,
        String contractSchemaJson,
        List<Map<String, Object>> fewShotSamples,
        String sessionSummary) {

    /** shape: drafting without a session continuity block. */
    public MockLlmFallbackGenerateRequest(Long spaceId, Long projectId, String method,
                                          String path, String maskedFingerprint,
                                          String contractSchemaJson,
                                          List<Map<String, Object>> fewShotSamples) {
        this(spaceId, projectId, method, path, maskedFingerprint,
                contractSchemaJson, fewShotSamples, null);
    }
}
