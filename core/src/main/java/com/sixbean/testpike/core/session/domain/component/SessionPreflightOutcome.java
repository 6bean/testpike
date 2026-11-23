package com.sixbean.testpike.core.session.domain.component;

import java.util.List;

/**
 * Verdict of one login-state preflight sweep.
 *
 * <p>{@code blockedLines} lists every case whose declared login state is
 * expired or could not be resolved — any entry refuses the batch start.
 * {@code warningLines} lists near-expiry and validity-unknown states: honest
 * notices, never an interception (the run stays allowed and a 401/403 hit
 * during execution marks the state invalid at once).
 *
 * @param blockedLines  refusal listing, one line per offending case
 * @param warningLines  non-blocking notices for the start result
 */
public record SessionPreflightOutcome(List<String> blockedLines,
                                      List<String> warningLines) {

    /** True when the batch must not start. */
    public boolean refused() {
        return blockedLines != null && !blockedLines.isEmpty();
    }
}
