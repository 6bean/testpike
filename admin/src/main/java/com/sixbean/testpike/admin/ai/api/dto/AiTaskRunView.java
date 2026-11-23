package com.sixbean.testpike.admin.ai.api.dto;

/**
 * One aggregated run row of the process event ledger, read through the
 * core single-source run-history door (R67 revisit entry). Carries the run
 * token, the frame count, whether the run hit an error frame and the
 * first/last landing timestamps — newest run first. Shared by every admin
 * bridge that offers a history list (login-state probe trail, credential
 * test trail); the per-run frame replay stays on the existing event window
 * endpoints, so no page re-implements the aggregation.
 */
public record AiTaskRunView(
        String runToken,
        long eventCount,
        boolean hasError,
        String firstAt,
        String lastAt) {
}
