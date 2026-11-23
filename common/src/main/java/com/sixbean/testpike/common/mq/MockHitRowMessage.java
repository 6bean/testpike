package com.sixbean.testpike.common.mq;

import java.util.List;
import java.util.Map;

/**
 * One already-masked traffic observation destined for the hit ledger
 * . The mock-server builds these rows
 * behind the desensitizer — PII never leaves the plane unmasked — and ships
 * them in {@link MockHitBatchMessage} batches over MQ; core is the only
 * writer of the ledger table (the data plane never touches PostgreSQL).
 *
 * @param method HTTP verb seen
 * @param path rule-relative path (below /mocks/{spaceKey})
 * @param query first value per query key, text-facets masked
 * @param headers lowercase header names, sensitive values already ***
 * @param body request body text masked through the shared pipeline (null = absent)
 * @param responseStatus stub-served HTTP status (null = no response plane on this row)
 * @param responseHeaders stub response headers, star-valued by the same gate (null = absent)
 * @param responseBody stub response body masked through the shared pipeline (null = absent)
 * @param matched whether a stub answered this request
 * @param stubId winner rule id (null on a miss)
 * @param stubName winner rule label for human display (null on a miss)
 * @param matchTrace per-matcher verdict rows as JSON text (inspector light-up source)
 * @param latencyMs serve time of this request
 * @param sessionId X-TP-Mock-Session lane (CI runs stay isolated through it; null = none)
 * @param occurredAt epoch millis when the request was served
 * @param faultApplied honest label of the injected fault that rode this response
 * (delay / disconnect / status / malformed / dribble / chaos;
 * null = normal served response — reports and badges consume it)
 * @param source honest provenance of the answering plane
 * (stub | llm | proxy; null = row predates the provenance
 * plane or a plain miss — never a fabricated default, the
 * X-TP-Mock-Source header and the ledger column share it)
 */
public record MockHitRowMessage(String method, String path, Map<String, String> query,
                                Map<String, String> headers, String body,
                                Integer responseStatus, Map<String, String> responseHeaders,
                                String responseBody,
                                boolean matched, Long stubId, String stubName,
                                String matchTrace, Integer latencyMs, String sessionId,
                                long occurredAt, String faultApplied, String source) {

    /** Pre-fault shape: rows captured before the injection plane existed. */
    public MockHitRowMessage(String method, String path, Map<String, String> query,
                             Map<String, String> headers, String body,
                             Integer responseStatus, Map<String, String> responseHeaders,
                             String responseBody,
                             boolean matched, Long stubId, String stubName,
                             String matchTrace, Integer latencyMs, String sessionId,
                             long occurredAt) {
        this(method, path, query, headers, body, responseStatus, responseHeaders, responseBody,
                matched, stubId, stubName, matchTrace, latencyMs, sessionId, occurredAt, null, null);
    }

    /** Pre-provenance shape: rows captured before the M10 honest-source plane. */
    public MockHitRowMessage(String method, String path, Map<String, String> query,
                             Map<String, String> headers, String body,
                             Integer responseStatus, Map<String, String> responseHeaders,
                             String responseBody,
                             boolean matched, Long stubId, String stubName,
                             String matchTrace, Integer latencyMs, String sessionId,
                             long occurredAt, String faultApplied) {
        this(method, path, query, headers, body, responseStatus, responseHeaders, responseBody,
                matched, stubId, stubName, matchTrace, latencyMs, sessionId, occurredAt,
                faultApplied, null);
    }
}
