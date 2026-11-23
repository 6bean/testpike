package com.sixbean.testpike.admin.judge.api.dto;

import java.util.List;

/**
 * Promote execution-batch task results into a new evaluation-set version
 * (AW2 source ②; mirrors core EvalSetBatchRaiseInput). createdBy is stamped
 * server-side from the login session. Terminal-state and missing-task gates
 * stay single-source in core; the bridge forwards without re-checking.
 *
 * @param setName   target evaluation set name
 * @param taskIds   execution task ids to promote
 * @param createdBy operator id (session-stamped)
 */
public record EvalSetBatchRaiseBody(String setName, List<Long> taskIds, Long createdBy) {
}
