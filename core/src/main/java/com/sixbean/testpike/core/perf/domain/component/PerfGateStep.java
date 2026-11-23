package com.sixbean.testpike.core.perf.domain.component;

/**
 * One step of the safety-gate ladder, exposed for the explainable precheck (CF-8).
 * <p>The UI renders the full ladder as a traffic light: every check reports its own
 * state instead of only the first rejection, and a failed step carries what is missing,
 * who can fix it, and where to fix it (entry point) so the operator can act immediately.
 *
 * @param code      stable machine key of the check (OWNERSHIP / BLOCKED_SEGMENT / GLOBAL_SWITCH /
 *                  WHITELIST / ISOLATION_DECLARATION / APPROVAL / ISOLATION_ALLOWED / TARGET_MATCH)
 * @param label     human-readable check name (shown as the ladder row title)
 * @param state     PASS / FAIL / SKIP (SKIP = the check does not apply to this launch, e.g.
 *                  production-only doors on a non-production environment)
 * @param detail    what was observed (PASS = short evidence line; FAIL = what is missing)
 * @param fixBy     who has the authority to fix a FAIL (empty for PASS/SKIP)
 * @param entryPath in-app navigation hint pointing at the fix entry (a route plus a section or
 *                  config-key name, localized to the user language; empty for PASS/SKIP)
 */
public record PerfGateStep(String code, String label, String state,
                           String detail, String fixBy, String entryPath) {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String SKIP = "SKIP";

    public static PerfGateStep pass(String code, String label, String detail) {
        return new PerfGateStep(code, label, PASS, detail, "", "");
    }

    public static PerfGateStep fail(String code, String label, String detail, String fixBy, String entryPath) {
        return new PerfGateStep(code, label, FAIL, detail, fixBy, entryPath);
    }

    public static PerfGateStep skip(String code, String label, String detail) {
        return new PerfGateStep(code, label, SKIP, detail, "", "");
    }

    public boolean failed() {
        return FAIL.equals(state);
    }
}
