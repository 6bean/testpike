package com.sixbean.testpike.core.mock.api.dto;

/**
 * A-3 LLM fallback lookup request read side; carries
 * the session lane).
 * <p>The traffic plane never sends raw PII across services: the request body
 * digest arrives already masked by the data plane (MaskingGate posture), and
 * the path arrives template-normalized ({@code /orders/{id}}) so one
 * generated answer serves the whole endpoint family instead of one literal id.
 *
 * @param spaceId serving space row id (cache is space-scoped by design)
 * @param method HTTP method, uppercase as adjudicated
 * @param path normalized endpoint path (template segments kept)
 * @param maskedFingerprint digest of the masked request (never enters logs verbatim)
 * @param sessionId lane of the exchange (X-TP-Mock-Session), may be
 * null — without a lane the session-summary context
 * simply stays absent
 */
public record MockLlmFallbackRequest(
        Long spaceId,
        String method,
        String path,
        String maskedFingerprint,
        String sessionId) {

    /** shape: an identity-only lookup carries no lane. */
    public MockLlmFallbackRequest(Long spaceId, String method, String path,
                                  String maskedFingerprint) {
        this(spaceId, method, path, maskedFingerprint, null);
    }
}
