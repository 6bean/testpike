package com.sixbean.testpike.admin.perf.api.dto;

/**
 * One row of the safety-gate explainable ladder (mirrors core PerfGateStep, field names
 * identical so the admin bridge receives the core JSON directly).
 *
 * @param code      stable machine key of the check (OWNERSHIP / BLOCKED_SEGMENT / GLOBAL_SWITCH /
 *                  WHITELIST / ISOLATION_DECLARATION / APPROVAL / ISOLATION_ALLOWED / TARGET_MATCH)
 * @param label     human-readable check name (ladder row title)
 * @param state     PASS / FAIL / SKIP
 * @param detail    observed fact; for FAIL = what is missing
 * @param fixBy     who has the authority to fix a FAIL (empty for PASS/SKIP)
 * @param entryPath in-app navigation hint for the fix entry (empty for PASS/SKIP)
 */
public record PerfGateStepView(String code, String label, String state,
                               String detail, String fixBy, String entryPath) {
}
