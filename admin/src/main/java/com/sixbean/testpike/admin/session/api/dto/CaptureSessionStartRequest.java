package com.sixbean.testpike.admin.session.api.dto;

import java.util.List;

/**
 * Wizard request to open one interactive capture session (AV1). Field names
 * mirror the core input record for direct mapping; the transient session id
 * comes back as a plain string — the ledger row is only created at the commit
 * round, and secret values never travel through this face.
 */
public record CaptureSessionStartRequest(
        long projectId,
        String stateName,
        List<String> scopeOrigins,
        String anchorKind,
        String anchorValue,
        String debugEndpoint,
        String mode) {
}
