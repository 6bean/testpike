package com.sixbean.testpike.core.mock.api.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * One request-inspector row — a hit-ledger record as the
 * inspector page consumes it. The jsonb facets arrive parsed (masked snapshot
 * map + verdict-trail list) so the bridge/UI never re-implements JSON handling;
 * values pass through exactly as stored, because the plane already ran them
 * through the desensitizer before publishing (core does NOT re-mask and must
 * not rewrite).
 *
 * @param id ledger row id
 * @param spaceId owning mock space (routing anchor of the query)
 * @param stubId matched stub; NULL = miss row (the "why didn't it fire" evidence)
 * @param requestSnapshot masked request facets as a map (method/path/query/headers/body/stubName)
 * @param responseSnapshot masked response facets as a map (status/headers/body); empty map when the row carries no response plane
 * @param matched hit verdict of this observation
 * @param matchTrace per-matcher verdict rows (empty list when the trail was unreadable)
 * @param latencyMs serve latency measured on the plane (null when unmeasured)
 * @param faultApplied fault kind applied to this hit (null = normal response)
 * @param sessionId session lane of the hit (X-TP-Mock-Session, null when anonymous)
 * @param hitAt serve instant on the plane — the inspector timeline sort key
 * @param source honest provenance of the answering plane (stub | llm | proxy;
 * NULL on pre-provenance or plain-miss rows — never a fabricated default)
 */
public record MockHitInfo(
        Long id,
        Long spaceId,
        Long stubId,
        Map<String, Object> requestSnapshot,
        Map<String, Object> responseSnapshot,
        Boolean matched,
        List<Map<String, Object>> matchTrace,
        Integer latencyMs,
        String faultApplied,
        String sessionId,
        LocalDateTime hitAt,
        String source) {
}
