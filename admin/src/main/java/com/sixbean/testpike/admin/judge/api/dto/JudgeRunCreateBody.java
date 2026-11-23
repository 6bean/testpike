package com.sixbean.testpike.admin.judge.api.dto;

/**
 * Create-an-evaluation-run request (AW4 dispatch form; mirrors core
 * RunCreateInput). createdBy is stamped server-side from the login session.
 * Anchor gates (unknown rubric / unknown set version / digest mismatch /
 * illegal target type) live single-source in core JudgeRunService — the
 * bridge forwards and rethrows core's plain-language rejection unchanged.
 *
 * @param rubricId       rubric version row id (pins which standard version)
 * @param evalsetName    evaluation set name
 * @param evalsetVersion evaluation set version
 * @param evalsetDigest  expected snapshot anchor (optional; when present core
 *                       rejects a mismatch — no stale-set rerun)
 * @param targetType     pipeline / external
 * @param targetRef      evaluated target reference
 * @param llmConfigId    judge model reference (optional = pure-rule scorecard)
 * @param createdBy      operator id (session-stamped)
 */
public record JudgeRunCreateBody(Long rubricId, String evalsetName, Integer evalsetVersion,
                                 String evalsetDigest, String targetType, String targetRef,
                                 Long llmConfigId, Long createdBy) {
}
