package com.sixbean.testpike.core.judge.api.dto;

import java.util.List;

/**
 * Golden-pack harvest input : the one-click collection
 * entry that freezes hand-picked adopted excellent AI cases into a new immutable
 * version of a golden eval set.
 *
 * @param setName target golden set name (same name again = next version, history immutable)
 * @param caseIds picked tp_test_case ids (empty/missing rows are rejected whole, human-worded)
 * @param createdBy operator id kept on every landed sample row
 */
public record AdoptedCaseRaiseInput(String setName, List<Long> caseIds, Long createdBy) {
}
