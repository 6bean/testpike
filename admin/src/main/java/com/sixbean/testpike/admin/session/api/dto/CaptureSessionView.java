package com.sixbean.testpike.admin.session.api.dto;

import java.util.List;

/**
 * Wizard-visible shape of a live capture session. Field names mirror the core
 * CaptureSessionInfo record; there is deliberately no debug-endpoint field
 * (the endpoint string must never echo back out of the session channel).
 */
public record CaptureSessionView(
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
