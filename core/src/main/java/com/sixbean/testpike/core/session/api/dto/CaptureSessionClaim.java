package com.sixbean.testpike.core.session.api.dto;

import java.util.List;

/**
 * Hand-off answer for one claimed capture session . This is the
 * only read shape that carries the CDP debug endpoint: it travels to the
 * executor over the internal protocol channel and nowhere else — the
 * admin-facing view has no such field (red line 4) and nothing here is ever
 * written to PostgreSQL.
 *
 * @param validateTimeoutMs executor-side read window, read fresh from the hot
 * session.validate-timeout-ms key (timeouts are never
 * hardcoded on the executor side)
 * @param attempt 1 for the first hand-off, incremented on every retry
 * @param commitSelection checked candidate names set by the wizard's commit
 * confirmation ; empty means this is a
 * metadata round, non-empty means "re-read these keys
 * only and answer on the commit-values door" — the
 * reverse signal rides the same hand-off queue, no
 */
public record CaptureSessionClaim(
        String sessionId,
        long projectId,
        String stateName,
        List<String> scopeOrigins,
        String anchorKind,
        String anchorValue,
        String debugEndpoint,
        String mode,
        int validateTimeoutMs,
        long attempt,
        List<String> commitSelection) {
}
