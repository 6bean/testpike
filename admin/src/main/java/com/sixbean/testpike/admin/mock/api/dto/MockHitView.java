package com.sixbean.testpike.admin.mock.api.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * One hit-ledger row as the admin bridge serves it (field-for-field mirror of
 * the core MockHitInfo record). Values pass through untouched: the plane
 * already desensitized every facet before publishing, and core rewrites
 * nothing on read — so a masked snapshot stays exactly as landed (red line 6:
 * no second processing on the bridge).
 */
public record MockHitView(
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
