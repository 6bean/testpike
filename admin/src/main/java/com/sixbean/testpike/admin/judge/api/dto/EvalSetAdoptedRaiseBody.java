package com.sixbean.testpike.admin.judge.api.dto;

import java.util.List;

/**
 * Promote hand-picked adopted AI cases into a new golden-set version
 * (AW2 source ①, BA core gate; mirrors core AdoptedCaseRaiseInput).
 * createdBy is stamped server-side from the login session. Eligibility
 * triple / requirement anchor / versioning gates stay single-source in core
 * JudgeSampleService — the bridge is a thin forwarder.
 *
 * @param setName target evaluation set name
 * @param caseIds adopted case ids to freeze
 * @param createdBy operator id (session-stamped)
 */
public record EvalSetAdoptedRaiseBody(String setName, List<Long> caseIds, Long createdBy) {
}
