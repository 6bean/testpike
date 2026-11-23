package com.sixbean.testpike.core.session.api.dto;

import java.util.List;

/**
 * Start-input for one interactive capture session (AV1 LOGIN_CAPTURE wizard).
 *
 * <p>The session is a short-lived human loop (operator logs in, the executor
 * reads scoped candidates, the operator picks and commits): everything here
 * lives only in the transient Redis session payload until commit registers the
 * chosen values through the vault. {@code debugEndpoint} is the operator's
 * local CDP debug endpoint string — red line 4 keeps it off every persistent
 * surface, so it is deliberately absent from the response shape.
 *
 * @param stateName     ledger name the resulting states will be registered under
 * @param scopeOrigins  origin whitelist (red line 2: empty = refuse, never "all")
 * @param anchorKind    login-success anchor type: url-prefix | target-element | token-key
 * @param anchorValue   anchor comparison value for the chosen anchor type
 * @param debugEndpoint CDP debug endpoint string of the operator's browser
 * @param mode          local-direct (default main path) | host-window (secondary shell)
 */
public record CaptureSessionInput(
        String stateName,
        List<String> scopeOrigins,
        String anchorKind,
        String anchorValue,
        String debugEndpoint,
        String mode) {
}
