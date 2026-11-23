package com.sixbean.testpike.core.mock.api.dto;

import java.util.List;
import java.util.Map;

/**
 * A-3 sync generation payload as the core orchestrator sends it to the
 * ai-service internal door . The HTTP shape of ai-service's
 * MockLlmFallbackGenerateRequest, kept lean here instead of sharing api DTOs
 * across modules (remote-brief pattern of the WxMock client family).
 *
 * @param spaceId serving space row id (cache is space-scoped)
 * @param projectId LLM routing anchor (project endpoint config wins)
 * @param method HTTP method of the unmatched request
 * @param path normalized endpoint path (template segments kept)
 * @param maskedFingerprint digest of the masked request (PII never travels)
 * @param contractSchemaJson endpoint contract schema as text, may be null —
 * wires the identity legs first; schema and
 * few-shot enrichment follow behind this same door
 * @param fewShotSamples recorded request/response pairs for the same
 * endpoint family (empty list = no examples)
 * @param sessionSummary plain lines of the lane's recent real exchanges
 * session-summary leg), may be null —
 * no lane, or nothing honestly recorded, = absent
 */
public record MockLlmFallbackGenerateInput(
        Long spaceId,
        Long projectId,
        String method,
        String path,
        String maskedFingerprint,
        String contractSchemaJson,
        List<Map<String, Object>> fewShotSamples,
        String sessionSummary) {
}
