package com.sixbean.testpike.core.session.api.dto;

import java.util.List;

/**
 * Read shape of one live capture session (wizard polling surface).
 *
 * <p>Metadata only: the debug endpoint string of red line 4 and any captured
 * value of red line 3 have no field here, so the response cannot echo either
 * even by accident. {@code candidateCount} is the live count of scoped
 * candidate entries the executor has written back so far (values stay inside
 * the executor process; only kind/name/origin/expiry metadata crosses).
 */
public record CaptureSessionInfo(
        String sessionId,
        long projectId,
        String stateName,
        List<String> scopeOrigins,
        String anchorKind,
        String anchorValue,
        String mode,
        String status,
        long candidateCount,
        long createdAtEpochSecond) {
}
