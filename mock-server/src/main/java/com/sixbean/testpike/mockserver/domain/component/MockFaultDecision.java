package com.sixbean.testpike.mockserver.domain.component;

/**
 * One fault-injection verdict for a served request (design F9, M7). A plain
 * top-level record — the codebase bans inner classes. {@code applied=false}
 * rows carry every other field at zero so callers can branch on one flag
 * without null checks.
 *
 * @param applied whether a fault rides this response at all
 * @param kind fault label written to the ledger: delay / disconnect /
 * status / malformed / dribble / chaos (null when not applied)
 * @param delayMs pre-response hold for delay faults (0 = none)
 * @param statusOverride 5xx status that replaces the stub status for status
 * and chaos faults (0 = serve the stub's own status)
 * @param chunkBytes dribble geometry: body segment size (0 = not a dribble)
 * @param chunkDelayMs dribble geometry: pause between slices
 */
public record MockFaultDecision(
        boolean applied,
        String kind,
        int delayMs,
        int statusOverride,
        int chunkBytes,
        int chunkDelayMs) {

    /** The normal serve path: no fault, stub answer rides untouched. */
    public static MockFaultDecision none() {
        return new MockFaultDecision(false, null, 0, 0, 0, 0);
    }
}
