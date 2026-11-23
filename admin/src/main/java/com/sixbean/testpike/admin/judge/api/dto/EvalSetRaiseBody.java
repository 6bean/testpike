package com.sixbean.testpike.admin.judge.api.dto;

import java.util.List;

/**
 * Raise training samples into a new evaluation-set version (AW2 source ③;
 * mirrors core EvalSetRaiseInput). createdBy is stamped server-side from the
 * login session — never trusted from the request payload. Eligibility gates
 * (missing sample ids, pairing rules, version bump) live single-source in
 * core JudgeSampleService; this bridge forwards with zero second validation.
 *
 * @param setName    target evaluation set name
 * @param sampleIds  training sample ids to promote
 * @param createdBy  operator id (session-stamped)
 */
public record EvalSetRaiseBody(String setName, List<Long> sampleIds, Long createdBy) {
}
